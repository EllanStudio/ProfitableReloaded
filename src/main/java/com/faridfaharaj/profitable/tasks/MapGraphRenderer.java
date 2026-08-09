package com.faridfaharaj.profitable.tasks;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.data.holderClasses.Candle;
import com.faridfaharaj.profitable.data.tables.Candles;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Renders an immutable snapshot of an asset's OHLC candles on a Minecraft map.
 *
 * <p>The renderer is deliberately non-contextual: every viewer sees the same
 * snapshot and Bukkit can share one canvas. A renderer instance paints that
 * shared canvas once, while its prepared data is immutable.</p>
 */
public final class MapGraphRenderer extends MapRenderer {

    static final int MAP_SIZE = 128;
    static final int MAX_VISIBLE_CANDLES = MAP_SIZE;

    private static final int HEADER_Y = 1;
    private static final int CHART_TOP = 11;
    private static final int CHART_BOTTOM = 126;
    private static final int MAX_VOLUME_HEIGHT = 28;
    private static final int MAX_GRID_LINES = 6;

    private static final Color VOLUME_COLOR = new Color(0x9B939393, true);
    private static final Color GRAPH_COLOR = new Color(0xFFFFFFFF, true);
    private static final Color GRID_COLOR = new Color(0x807A7A7A, true);
    private static final Color DEFAULT_BULLISH_COLOR = new Color(0x8CD740);
    private static final Color DEFAULT_BEARISH_COLOR = new Color(0xFA413B);

    private final String title;
    private final PreparedSeries series;
    private boolean rendered;

    public MapGraphRenderer(String asset, String interval, List<Candle> candles) {
        super(false);
        this.title = sanitizeLabel(asset) + " (" + sanitizeLabel(interval) + ")";
        this.series = prepareSeries(candles);
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        Objects.requireNonNull(canvas, "canvas");

        // Non-contextual renderers share a canvas. Synchronizing prevents two
        // initial render callbacks from interleaving and only marks completion
        // after every pixel has been written, so a failed paint can be retried.
        synchronized (this) {
            if (rendered) {
                return;
            }
            draw(canvas);
            rendered = true;
        }
    }

    private void draw(MapCanvas canvas) {
        drawTextFitted(canvas, 1, HEADER_Y, title, MAP_SIZE - 2);
        rectangle(canvas, 0, CHART_TOP - 1, MAP_SIZE - 1, CHART_TOP - 1, GRAPH_COLOR);
        rectangle(canvas, 0, CHART_BOTTOM + 1, MAP_SIZE - 1, CHART_BOTTOM + 1, GRAPH_COLOR);

        if (series.candles().isEmpty()) {
            drawCenteredText(canvas, (CHART_TOP + CHART_BOTTOM) / 2 - 3, "No data");
            return;
        }

        double displayLow = series.lowest();
        double displayHigh = series.highest();
        boolean flat = Double.compare(displayLow, displayHigh) == 0;

        double scaleLow = displayLow;
        double scaleHigh = displayHigh;
        if (flat) {
            double padding = Math.max(Math.abs(displayLow) * 0.01, Math.ulp(displayLow) * 8.0);
            if (!Double.isFinite(padding) || padding <= 0) {
                padding = 1.0;
            }
            scaleLow = Math.max(0.0, displayLow - padding);
            scaleHigh = displayLow + padding;
            if (!Double.isFinite(scaleHigh) || Double.compare(scaleLow, scaleHigh) == 0) {
                // At Double.MAX_VALUE no representable upper padding exists.
                scaleLow = Math.nextDown(displayLow);
                scaleHigh = displayLow;
            }
        }

        List<GridTick> gridTicks = flat ? List.of() : gridTicks(scaleLow, scaleHigh);
        for (GridTick tick : gridTicks) {
            dottedHorizontalLine(canvas, tick.y(), GRID_COLOR);
        }
        drawCandles(canvas, scaleLow, scaleHigh, flat);
        for (GridTick tick : gridTicks) {
            drawTextFitted(canvas, 1, tick.y() + 1, formatPrice(tick.price()), 45);
        }

        if (flat) {
            String label = formatPrice(displayLow);
            drawTextFitted(canvas, 1,
                    (CHART_TOP + CHART_BOTTOM) / 2 - 3,
                    label, MAP_SIZE - 2);
        } else {
            drawTextFitted(canvas, 1, CHART_TOP + 1, formatPrice(displayHigh), MAP_SIZE - 2);
            drawTextFitted(canvas, 1, CHART_BOTTOM - 7, formatPrice(displayLow), MAP_SIZE - 2);
        }
    }

    private static List<GridTick> gridTicks(double low, double high) {
        double step = niceStep(high - low, 4);
        if (!Double.isFinite(step) || step <= 0) {
            return List.of();
        }

        double first = Math.ceil(low / step) * step;
        if (!Double.isFinite(first)) {
            return List.of();
        }

        List<GridTick> ticks = new ArrayList<>(MAX_GRID_LINES);
        for (int i = 0; i < MAX_GRID_LINES; i++) {
            double price = first + step * i;
            if (!Double.isFinite(price) || price >= high) {
                break;
            }
            if (price <= low) {
                continue;
            }

            int y = priceToY(price, low, high);
            if (y <= CHART_TOP + 8 || y >= CHART_BOTTOM - 8) {
                continue;
            }
            ticks.add(new GridTick(price, y));
        }
        return List.copyOf(ticks);
    }

    private void drawCandles(MapCanvas canvas, double low, double high, boolean flat) {
        List<Candle> candles = series.candles();
        int count = candles.size();

        for (int i = 0; i < count; i++) {
            Candle candle = candles.get(i);
            int left = (int) (((long) i * MAP_SIZE) / count);
            int right = (int) (((long) (i + 1) * MAP_SIZE) / count) - 1;
            if (i == count - 1) {
                right = MAP_SIZE - 1;
            }
            right = Math.max(left, right);

            int center = left + (right - left) / 2;
            int bodyLeft = right - left >= 2 ? left + 1 : left;
            int bodyRight = right - left >= 2 ? right - 1 : right;
            if (bodyLeft > bodyRight) {
                bodyLeft = center;
                bodyRight = center;
            }

            if (candle.getVolume() > 0 && series.maxVolume() > 0) {
                double ratio = clamp01(candle.getVolume() / series.maxVolume());
                int height = Math.max(1, (int) Math.round(ratio * MAX_VOLUME_HEIGHT));
                transparentRectangle(canvas, left, CHART_BOTTOM,
                        right, CHART_BOTTOM - height + 1, VOLUME_COLOR);
            }

            int flatY = (CHART_TOP + CHART_BOTTOM) / 2;
            int openY = flat ? flatY : priceToY(candle.getOpen(), low, high);
            int closeY = flat ? flatY : priceToY(candle.getClose(), low, high);
            int highY = flat ? flatY : priceToY(candle.getHigh(), low, high);
            int lowY = flat ? flatY : priceToY(candle.getLow(), low, high);
            Color color = candle.getClose() < candle.getOpen() ? bearishColor() : bullishColor();

            rectangle(canvas, center, highY, center, lowY, color);
            shadedRectangle(canvas, bodyLeft, Math.min(openY, closeY),
                    bodyRight, Math.max(openY, closeY), color);
        }
    }

    private static Color bullishColor() {
        return Configuration.COLORBULLISH == null
                ? DEFAULT_BULLISH_COLOR
                : new Color(Configuration.COLORBULLISH.value());
    }

    private static Color bearishColor() {
        return Configuration.COLORBEARISH == null
                ? DEFAULT_BEARISH_COLOR
                : new Color(Configuration.COLORBEARISH.value());
    }

    /**
     * Removes the legacy summary sentinel, rejects corrupt values, repairs
     * inconsistent OHLC bounds, and aggregates oversized histories into at
     * most one OHLC bucket per map column.
     */
    static PreparedSeries prepareSeries(List<Candle> source) {
        if (source == null || source.isEmpty()) {
            return PreparedSeries.empty();
        }

        int end = source.size();
        Candle finalEntry = source.get(end - 1);
        if (isLegacySummary(finalEntry)) {
            end--;
        }

        List<Candle> valid = new ArrayList<>(Math.min(end, MAX_VISIBLE_CANDLES));
        for (int i = 0; i < end; i++) {
            Candle candle = source.get(i);
            Candle normalized = normalize(candle);
            if (normalized != null) {
                valid.add(normalized);
            }
        }

        if (valid.isEmpty()) {
            return PreparedSeries.empty();
        }

        List<Candle> sampled = aggregateCandles(valid, MAX_VISIBLE_CANDLES);
        double lowest = Double.POSITIVE_INFINITY;
        double highest = Double.NEGATIVE_INFINITY;
        double maxVolume = 0.0;
        for (Candle candle : sampled) {
            lowest = Math.min(lowest, candle.getLow());
            highest = Math.max(highest, candle.getHigh());
            maxVolume = Math.max(maxVolume, candle.getVolume());
        }
        return new PreparedSeries(sampled, lowest, highest, maxVolume);
    }

    private static boolean isLegacySummary(Candle candle) {
        return candle != null
                && Double.compare(candle.getOpen(), -1.0) == 0
                && Double.compare(candle.getClose(), -1.0) == 0;
    }

    private static Candle normalize(Candle candle) {
        if (candle == null
                || !positiveFinite(candle.getOpen())
                || !positiveFinite(candle.getClose())
                || !positiveFinite(candle.getHigh())
                || !positiveFinite(candle.getLow())
                || !Double.isFinite(candle.getVolume())
                || candle.getVolume() < 0) {
            return null;
        }

        double high = Math.max(Math.max(candle.getHigh(), candle.getLow()),
                Math.max(candle.getOpen(), candle.getClose()));
        double low = Math.min(Math.min(candle.getHigh(), candle.getLow()),
                Math.min(candle.getOpen(), candle.getClose()));
        return new Candle(candle.getOpen(), candle.getClose(), high, low, candle.getVolume());
    }

    private static boolean positiveFinite(double value) {
        return Double.isFinite(value) && value > 0;
    }

    static List<Candle> aggregateCandles(List<Candle> source, int maximum) {
        Objects.requireNonNull(source, "source");
        if (maximum <= 0) {
            throw new IllegalArgumentException("maximum must be positive");
        }
        if (source.size() <= maximum) {
            return List.copyOf(source);
        }

        List<Candle> sampled = new ArrayList<>(maximum);
        int size = source.size();
        for (int bucket = 0; bucket < maximum; bucket++) {
            int start = (int) (((long) bucket * size) / maximum);
            int end = (int) (((long) (bucket + 1) * size) / maximum);
            Candle first = source.get(start);
            Candle last = source.get(end - 1);
            double high = Double.NEGATIVE_INFINITY;
            double low = Double.POSITIVE_INFINITY;
            double volume = 0.0;

            for (int i = start; i < end; i++) {
                Candle candle = source.get(i);
                high = Math.max(high, candle.getHigh());
                low = Math.min(low, candle.getLow());
                volume = saturatedAdd(volume, candle.getVolume());
            }
            sampled.add(new Candle(first.getOpen(), last.getClose(), high, low, volume));
        }
        return List.copyOf(sampled);
    }

    private static double saturatedAdd(double left, double right) {
        double result = left + right;
        return Double.isFinite(result) ? result : Double.MAX_VALUE;
    }

    static int priceToY(double price, double low, double high) {
        return priceToY(price, low, high, CHART_TOP, CHART_BOTTOM);
    }

    static int priceToY(double price, double low, double high, int top, int bottom) {
        if (!Double.isFinite(price) || !Double.isFinite(low) || !Double.isFinite(high)
                || high <= low || bottom <= top) {
            return top + Math.max(0, bottom - top) / 2;
        }

        double scale = Math.max(Math.abs(low), Math.abs(high));
        double denominator = high / scale - low / scale;
        double ratio = denominator > 0 ? (price / scale - low / scale) / denominator : 0.5;
        ratio = clamp01(ratio);
        return bottom - (int) Math.round(ratio * (bottom - top));
    }

    static double niceStep(double range, int targetLines) {
        if (!Double.isFinite(range) || range <= 0 || targetLines <= 0) {
            return Double.NaN;
        }
        double rough = range / targetLines;
        double exponent = Math.pow(10.0, Math.floor(Math.log10(rough)));
        if (!Double.isFinite(exponent) || exponent == 0) {
            return rough;
        }
        double fraction = rough / exponent;
        double niceFraction;
        if (fraction <= 1) {
            niceFraction = 1;
        } else if (fraction <= 2) {
            niceFraction = 2;
        } else if (fraction <= 5) {
            niceFraction = 5;
        } else {
            niceFraction = 10;
        }
        double result = niceFraction * exponent;
        return Double.isFinite(result) && result > 0 ? result : rough;
    }

    static String formatPrice(double value) {
        if (!Double.isFinite(value)) {
            return "--";
        }

        double absolute = Math.abs(value);
        if (absolute >= 1_000_000_000_000_000.0) {
            return String.format(Locale.ROOT, "%.2E", value).replace("E+", "E");
        }
        if (absolute >= 1_000_000_000_000.0) {
            return trimDecimal(value / 1_000_000_000_000.0, 2) + "T";
        }
        if (absolute >= 1_000_000_000.0) {
            return trimDecimal(value / 1_000_000_000.0, 2) + "B";
        }
        if (absolute >= 1_000_000.0) {
            return trimDecimal(value / 1_000_000.0, 2) + "M";
        }
        if (absolute >= 1_000.0) {
            return trimDecimal(value / 1_000.0, 2) + "K";
        }
        if (absolute >= 100.0) {
            return trimDecimal(value, 1);
        }
        if (absolute >= 1.0) {
            return trimDecimal(value, 2);
        }
        if (absolute >= 0.01 || absolute == 0.0) {
            return trimDecimal(value, 4);
        }
        return String.format(Locale.ROOT, "%.2E", value).replace("E+", "E");
    }

    private static String trimDecimal(double value, int decimals) {
        String text = String.format(Locale.ROOT, "%." + decimals + "f", value);
        if (text.indexOf('.') >= 0) {
            text = text.replaceFirst("0+$", "").replaceFirst("\\.$", "");
        }
        return text;
    }

    private static String sanitizeLabel(String label) {
        if (label == null || label.isBlank()) {
            return "?";
        }
        StringBuilder safe = new StringBuilder(Math.min(label.length(), 64));
        for (int i = 0; i < label.length() && safe.length() < 64; i++) {
            char character = label.charAt(i);
            safe.append(character >= 32 && character <= 126 && character != '\u00a7' ? character : '?');
        }
        return safe.toString();
    }

    static String fitText(String text, int maximumWidth) {
        String safe = sanitizeLabel(text);
        if (maximumWidth <= 0) {
            return "";
        }
        if (MinecraftFont.Font.getWidth(safe) <= maximumWidth) {
            return safe;
        }

        String suffix = "...";
        int suffixWidth = MinecraftFont.Font.getWidth(suffix);
        if (suffixWidth > maximumWidth) {
            return "";
        }

        StringBuilder fitted = new StringBuilder();
        for (int i = 0; i < safe.length(); i++) {
            String candidate = fitted.toString() + safe.charAt(i) + suffix;
            if (MinecraftFont.Font.getWidth(candidate) > maximumWidth) {
                break;
            }
            fitted.append(safe.charAt(i));
        }
        return fitted + suffix;
    }

    private static void drawTextFitted(MapCanvas canvas, int x, int y, String text, int maximumWidth) {
        String fitted = fitText(text, maximumWidth);
        if (!fitted.isEmpty()) {
            canvas.drawText(clamp(x, 0, MAP_SIZE - 1), clamp(y, 0, MAP_SIZE - 1),
                    MinecraftFont.Font, fitted);
        }
    }

    private static void drawCenteredText(MapCanvas canvas, int y, String text) {
        String fitted = fitText(text, MAP_SIZE - 2);
        int width = MinecraftFont.Font.getWidth(fitted);
        drawTextFitted(canvas, Math.max(0, (MAP_SIZE - width) / 2), y, fitted, MAP_SIZE - 2);
    }

    private static void dottedHorizontalLine(MapCanvas canvas, int y, Color color) {
        int boundedY = clamp(y, 0, MAP_SIZE - 1);
        for (int x = 0; x < MAP_SIZE; x += 3) {
            canvas.setPixelColor(x, boundedY, blendColors(canvas.getPixelColor(x, boundedY), color));
        }
    }

    public static void rectangle(MapCanvas canvas, int initX, int initY,
                                 int targetX, int targetY, Color color) {
        if (outsideCanvas(initX, initY, targetX, targetY)) {
            return;
        }
        int minX = clamp(Math.min(initX, targetX), 0, MAP_SIZE - 1);
        int maxX = clamp(Math.max(initX, targetX), 0, MAP_SIZE - 1);
        int minY = clamp(Math.min(initY, targetY), 0, MAP_SIZE - 1);
        int maxY = clamp(Math.max(initY, targetY), 0, MAP_SIZE - 1);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                canvas.setPixelColor(x, y, color);
            }
        }
    }

    public static void transparentRectangle(MapCanvas canvas, int initX, int initY,
                                            int targetX, int targetY, Color color) {
        if (outsideCanvas(initX, initY, targetX, targetY)) {
            return;
        }
        int minX = clamp(Math.min(initX, targetX), 0, MAP_SIZE - 1);
        int maxX = clamp(Math.max(initX, targetX), 0, MAP_SIZE - 1);
        int minY = clamp(Math.min(initY, targetY), 0, MAP_SIZE - 1);
        int maxY = clamp(Math.max(initY, targetY), 0, MAP_SIZE - 1);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                canvas.setPixelColor(x, y, blendColors(canvas.getPixelColor(x, y), color));
            }
        }
    }

    public static void shadedRectangle(MapCanvas canvas, int initX, int initY,
                                       int targetX, int targetY, Color color) {
        if (outsideCanvas(initX, initY, targetX, targetY)) {
            return;
        }
        int minX = clamp(Math.min(initX, targetX), 0, MAP_SIZE - 1);
        int maxX = clamp(Math.max(initX, targetX), 0, MAP_SIZE - 1);
        int minY = clamp(Math.min(initY, targetY), 0, MAP_SIZE - 1);
        int maxY = clamp(Math.max(initY, targetY), 0, MAP_SIZE - 1);
        rectangle(canvas, minX, minY, maxX, maxY, color);
        if (maxX > minX) {
            rectangle(canvas, maxX, minY, maxX, maxY, color.darker());
        }
        if (maxY > minY) {
            rectangle(canvas, minX, maxY, maxX, maxY, color.darker());
        }
    }

    /** Retained for binary/source compatibility with earlier graph helpers. */
    public static void DIERectangle(MapCanvas canvas, int initX, int initY,
                                    int targetX, int targetY, Color color) {
        if (outsideCanvas(initX, initY, targetX, targetY)) {
            return;
        }
        int minX = clamp(Math.min(initX, targetX), 0, MAP_SIZE - 1);
        int maxX = clamp(Math.max(initX, targetX), 0, MAP_SIZE - 1);
        int minY = clamp(Math.min(initY, targetY), 0, MAP_SIZE - 1);
        int maxY = clamp(Math.max(initY, targetY), 0, MAP_SIZE - 1);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                if (canvas.getPixelColor(x, y) == null) {
                    canvas.setPixelColor(x, y, color);
                }
            }
        }
    }

    public static List<Candle> loadCandles(World world, String assetId, long lookback, long currentTime) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(assetId, "assetId");

        int intervalIndex = intervalIndexFor(lookback);
        long intervalTicks = intervalTicksFor(lookback);
        long alignedTime = Math.floorDiv(currentTime, intervalTicks) * intervalTicks;
        long safeLookback = Math.max(0, lookback);
        long startTime;
        try {
            startTime = Math.subtractExact(alignedTime, safeLookback);
        } catch (ArithmeticException ignored) {
            startTime = Long.MIN_VALUE;
        }
        return prepareSeries(Candles.getInterval(world, assetId, startTime, intervalIndex)).candles();
    }

    static int intervalIndexFor(long lookback) {
        if (lookback > 5_376_000) {
            return 2;
        }
        if (lookback > 768_000) {
            return 1;
        }
        return 0;
    }

    static long intervalTicksFor(long lookback) {
        return switch (intervalIndexFor(lookback)) {
            case 2 -> 720_000L;
            case 1 -> 168_000L;
            default -> 24_000L;
        };
    }

    public static ItemStack createGraphMap(Player player, String assetId,
                                           String interval, List<Candle> candles) {
        Objects.requireNonNull(player, "player");
        return createGraphMap(player.getWorld(), assetId, interval, candles);
    }

    public static ItemStack createGraphMap(World world, String assetId,
                                           String interval, List<Candle> candles) {
        Objects.requireNonNull(world, "world");
        MapView mapView = Bukkit.createMap(world);
        List.copyOf(mapView.getRenderers()).forEach(mapView::removeRenderer);
        mapView.setTrackingPosition(false);
        mapView.setUnlimitedTracking(false);
        mapView.setLocked(true);
        mapView.addRenderer(new MapGraphRenderer(assetId, interval, candles));

        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        if (!(mapItem.getItemMeta() instanceof MapMeta meta)) {
            throw new IllegalStateException("FILLED_MAP did not provide MapMeta");
        }
        meta.setMapView(mapView);
        mapItem.setItemMeta(meta);
        return mapItem;
    }

    public static Color blendColors(Color base, Color overlay) {
        Objects.requireNonNull(overlay, "overlay");
        if (base == null) {
            return overlay;
        }

        float alphaOver = overlay.getAlpha() / 255.0f;
        float alphaBase = base.getAlpha() / 255.0f;
        int red = clamp(Math.round(overlay.getRed() * alphaOver + base.getRed() * (1 - alphaOver)), 0, 255);
        int green = clamp(Math.round(overlay.getGreen() * alphaOver + base.getGreen() * (1 - alphaOver)), 0, 255);
        int blue = clamp(Math.round(overlay.getBlue() * alphaOver + base.getBlue() * (1 - alphaOver)), 0, 255);
        int alpha = clamp(Math.round((alphaOver + alphaBase * (1 - alphaOver)) * 255), 0, 255);
        return new Color(red, green, blue, alpha);
    }

    public static Color blendColors(Color base, Color overlay, float amount) {
        Objects.requireNonNull(overlay, "overlay");
        float boundedAmount = Math.max(0, Math.min(255, amount));
        Color adjustedOverlay = new Color(overlay.getRed(), overlay.getGreen(), overlay.getBlue(),
                Math.round(boundedAmount));
        return blendColors(base, adjustedOverlay);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return value > 0 ? 1.0 : 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean outsideCanvas(int initX, int initY, int targetX, int targetY) {
        return Math.max(initX, targetX) < 0
                || Math.min(initX, targetX) >= MAP_SIZE
                || Math.max(initY, targetY) < 0
                || Math.min(initY, targetY) >= MAP_SIZE;
    }

    record PreparedSeries(List<Candle> candles, double lowest, double highest, double maxVolume) {
        PreparedSeries {
            candles = List.copyOf(candles);
        }

        static PreparedSeries empty() {
            return new PreparedSeries(List.of(), Double.NaN, Double.NaN, 0.0);
        }
    }

    private record GridTick(double price, int y) {
    }
}
