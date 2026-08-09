package com.faridfaharaj.profitable.tasks;

import com.faridfaharaj.profitable.data.holderClasses.Candle;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapFont;
import org.bukkit.map.MinecraftFont;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Image;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapGraphRendererTest {

    @AfterEach
    void clearGraphCache() {
        TemporalItems.clearGraphCache();
    }

    @Test
    void treatsNullEmptyAndLegacySummaryOnlyInputAsNoData() {
        Candle summary = new Candle(-1, -1, 100, 10, 25);

        assertTrue(MapGraphRenderer.prepareSeries(null).candles().isEmpty());
        assertTrue(MapGraphRenderer.prepareSeries(List.of()).candles().isEmpty());
        assertTrue(MapGraphRenderer.prepareSeries(List.of(summary)).candles().isEmpty());
    }

    @Test
    void keepsASingleRealCandleAndDropsTheLegacySummary() {
        Candle candle = new Candle(10, 11, 12, 9, 4);
        Candle summary = new Candle(-1, -1, 12, 9, 4);

        MapGraphRenderer.PreparedSeries prepared =
                MapGraphRenderer.prepareSeries(List.of(candle, summary));

        assertEquals(1, prepared.candles().size());
        assertEquals(10, prepared.candles().getFirst().getOpen());
        assertEquals(11, prepared.candles().getFirst().getClose());
        assertEquals(9, prepared.lowest());
        assertEquals(12, prepared.highest());
        assertEquals(4, prepared.maxVolume());
        assertThrows(UnsupportedOperationException.class,
                () -> prepared.candles().add(candle));
    }

    @Test
    void rejectsCorruptCandlesAndRepairsInconsistentOhlcBounds() {
        List<Candle> source = List.of(
                new Candle(10, 12, 5, 20, 3),
                new Candle(Double.NaN, 1, 1, 1, 1),
                new Candle(0, 1, 1, 1, 1),
                new Candle(1, 1, 1, 1, -1)
        );

        MapGraphRenderer.PreparedSeries prepared = MapGraphRenderer.prepareSeries(source);

        assertEquals(1, prepared.candles().size());
        Candle normalized = prepared.candles().getFirst();
        assertEquals(5, normalized.getLow());
        assertEquals(20, normalized.getHigh());
        assertEquals(10, normalized.getOpen());
        assertEquals(12, normalized.getClose());
    }

    @Test
    void aggregatesLongHistoriesToOneOhlcBucketPerMapColumn() {
        List<Candle> source = new ArrayList<>();
        for (int i = 0; i < 1_024; i++) {
            double base = i + 1;
            source.add(new Candle(base, base + 0.5, base + 1, base - 0.5, 1));
        }

        MapGraphRenderer.PreparedSeries prepared = MapGraphRenderer.prepareSeries(source);

        assertEquals(128, prepared.candles().size());
        assertEquals(1, prepared.candles().getFirst().getOpen());
        assertEquals(1_024.5, prepared.candles().getLast().getClose());
        assertEquals(0.5, prepared.lowest());
        assertEquals(1_025, prepared.highest());
        assertEquals(8, prepared.maxVolume());
        assertEquals(1_024,
                prepared.candles().stream().mapToDouble(Candle::getVolume).sum());
    }

    @Test
    void priceScalingAlwaysStaysInsideTheRequestedPixelBounds() {
        int top = 11;
        int bottom = 126;

        assertEquals(bottom, MapGraphRenderer.priceToY(Double.MIN_VALUE,
                Double.MIN_VALUE, Double.MAX_VALUE, top, bottom));
        assertEquals(top, MapGraphRenderer.priceToY(Double.MAX_VALUE,
                Double.MIN_VALUE, Double.MAX_VALUE, top, bottom));
        assertEquals((top + bottom) / 2,
                MapGraphRenderer.priceToY(5, 5, 5, top, bottom));

        int middle = MapGraphRenderer.priceToY(500, 1, 1_000, top, bottom);
        assertTrue(middle >= top && middle <= bottom);
    }

    @Test
    void formatsExtremePricesCompactlyAndClipsUnsafeLabels() {
        assertEquals("--", MapGraphRenderer.formatPrice(Double.NaN));
        assertEquals("1.5K", MapGraphRenderer.formatPrice(1_500));
        assertTrue(MapGraphRenderer.formatPrice(Double.MAX_VALUE).length() <= 9);
        assertTrue(MapGraphRenderer.formatPrice(Double.MIN_VALUE).length() <= 10);

        String fitted = MapGraphRenderer.fitText("ASSET\nWITH\u00a7FORMAT-CONTROL", 35);
        assertFalse(fitted.contains("\n"));
        assertFalse(fitted.contains("§"));
        assertTrue(MinecraftFont.Font.getWidth(fitted) <= 35);
    }

    @Test
    void rendererIsExplicitlyNonContextual() {
        MapGraphRenderer renderer = new MapGraphRenderer(
                "ASSET", "1M", List.of(new Candle(1, 1, 1, 1, 0)));

        assertFalse(renderer.isContextual());
    }

    @Test
    void renderHandlesSingleFlatExtremeAndDenseSeriesWithoutLeavingTheCanvas() {
        List<List<Candle>> inputs = List.of(
                List.of(new Candle(1, 1, 1, 1, 0)),
                List.of(new Candle(Double.MIN_VALUE, Double.MIN_VALUE,
                        Double.MIN_VALUE, Double.MIN_VALUE, Double.MAX_VALUE)),
                List.of(new Candle(Double.MAX_VALUE, Double.MAX_VALUE,
                        Double.MAX_VALUE, Math.nextDown(Double.MAX_VALUE), Double.MAX_VALUE)),
                createDenseSeries(2_000)
        );

        for (List<Candle> input : inputs) {
            RecordingCanvas canvas = new RecordingCanvas();
            MapGraphRenderer renderer = new MapGraphRenderer(
                    "VERY-LONG-ASSET-NAME-WITH-UNSAFE\nTEXT", "2Y", input);

            renderer.render(null, canvas, null);
            int firstRenderWrites = canvas.pixelWrites;
            assertTrue(firstRenderWrites > 0);
            assertTrue(canvas.textWrites > 0);

            renderer.render(null, canvas, null);
            assertEquals(firstRenderWrites, canvas.pixelWrites,
                    "a non-contextual renderer must not repaint an unchanged map");
        }
    }

    @Test
    void rectangleHelpersClipPartialShapesAndIgnoreFullyOffCanvasShapes() {
        RecordingCanvas canvas = new RecordingCanvas();

        MapGraphRenderer.rectangle(canvas, -10, -10, -1, -1, Color.WHITE);
        MapGraphRenderer.transparentRectangle(canvas, 128, 128, 140, 140, Color.WHITE);
        assertEquals(0, canvas.pixelWrites);

        MapGraphRenderer.rectangle(canvas, -1, -1, 0, 0, Color.WHITE);
        assertEquals(1, canvas.pixelWrites);
    }

    @Test
    void selectsTheExpectedCandleResolutionAtBoundaries() {
        assertEquals(0, MapGraphRenderer.intervalIndexFor(768_000));
        assertEquals(1, MapGraphRenderer.intervalIndexFor(768_001));
        assertEquals(1, MapGraphRenderer.intervalIndexFor(5_376_000));
        assertEquals(2, MapGraphRenderer.intervalIndexFor(5_376_001));
        assertEquals(24_000, MapGraphRenderer.intervalTicksFor(0));
        assertEquals(720_000, MapGraphRenderer.intervalTicksFor(17_520_000));
    }

    @Test
    void graphSnapshotCacheReusesAndThenExpiresEntries() {
        TemporalItems.GraphCacheKey key = new TemporalItems.GraphCacheKey(
                UUID.randomUUID(), "ASSET", 720_000, 24_000, 10);
        AtomicInteger loads = new AtomicInteger();
        List<Candle> candles = List.of(new Candle(1, 2, 2, 1, 3));

        assertEquals(candles, TemporalItems.getOrLoadGraph(key, 0, () -> {
            loads.incrementAndGet();
            return candles;
        }));
        assertEquals(candles, TemporalItems.getOrLoadGraph(
                key, TemporalItems.GRAPH_CACHE_TTL_NANOS - 1, () -> {
                    loads.incrementAndGet();
                    return List.of();
                }));
        assertEquals(1, loads.get());

        TemporalItems.getOrLoadGraph(key, TemporalItems.GRAPH_CACHE_TTL_NANOS, () -> {
            loads.incrementAndGet();
            return candles;
        });
        assertEquals(2, loads.get());
    }

    @Test
    void graphSnapshotCacheRemainsBounded() {
        UUID worldId = UUID.randomUUID();
        for (int i = 0; i < TemporalItems.GRAPH_CACHE_MAX_ENTRIES + 20; i++) {
            TemporalItems.GraphCacheKey key = new TemporalItems.GraphCacheKey(
                    worldId, "ASSET-" + i, 720_000, 24_000, i);
            TemporalItems.getOrLoadGraph(key, 0,
                    () -> List.of(new Candle(1, 1, 1, 1, 0)));
        }

        assertTrue(TemporalItems.graphCacheSize() <= TemporalItems.GRAPH_CACHE_MAX_ENTRIES);
    }

    private static List<Candle> createDenseSeries(int size) {
        List<Candle> candles = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            double base = i + 1;
            candles.add(new Candle(base, base + 0.25, base + 0.5, base - 0.5, i + 1));
        }
        return candles;
    }

    private static final class RecordingCanvas implements MapCanvas {
        private final Color[][] pixels = new Color[128][128];
        private int pixelWrites;
        private int textWrites;

        @Override
        public MapView getMapView() {
            return null;
        }

        @Override
        public MapCursorCollection getCursors() {
            return null;
        }

        @Override
        public void setCursors(MapCursorCollection cursors) {
        }

        @Override
        public void setPixelColor(int x, int y, Color color) {
            assertInside(x, y);
            pixels[x][y] = color;
            pixelWrites++;
        }

        @Override
        public Color getPixelColor(int x, int y) {
            assertInside(x, y);
            return pixels[x][y];
        }

        @Override
        public Color getBasePixelColor(int x, int y) {
            assertInside(x, y);
            return null;
        }

        @Override
        public void setPixel(int x, int y, byte color) {
            assertInside(x, y);
            pixelWrites++;
        }

        @Override
        public byte getPixel(int x, int y) {
            assertInside(x, y);
            return 0;
        }

        @Override
        public byte getBasePixel(int x, int y) {
            assertInside(x, y);
            return 0;
        }

        @Override
        public void drawImage(int x, int y, Image image) {
            assertInside(x, y);
        }

        @Override
        public void drawText(int x, int y, MapFont font, String text) {
            assertInside(x, y);
            assertTrue(x + font.getWidth(text) <= 128,
                    () -> "text exceeded canvas width: " + text);
            assertTrue(y + font.getHeight() <= 128,
                    () -> "text exceeded canvas height: " + text);
            textWrites++;
        }

        private static void assertInside(int x, int y) {
            assertTrue(x >= 0 && x < 128, () -> "x outside map: " + x);
            assertTrue(y >= 0 && y < 128, () -> "y outside map: " + y);
        }
    }
}
