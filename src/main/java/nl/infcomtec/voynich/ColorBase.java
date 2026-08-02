/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.TreeMap;

/**
 * Manages a two-level cache of RGB-to-CIELab colour conversions.
 * <p>
 * Colour conversion (RGB → CIELab) is expensive. This class avoids redundant
 * conversions through two caches:
 * <ul>
 * <li>{@link #cache} — an instance-level cache, one per {@code ColorBase}.</li>
 * <li>{@link #convert} — a static, cross-instance cache shared by all
 * {@code ColorBase} instances in the JVM.</li>
 * </ul>
 * Lookup order when {@code add()} is called: instance cache first (early
 * return, no allocation), static cache second (LAB copied, new
 * {@code TriLabColor} registered only in instance cache), full conversion last
 * (result written to both caches). A colour that is in {@code convert} but not
 * in the current instance cache — typical for colours shared across images when
 * each image gets its own {@code ColorBase} — causes one extra
 * {@code TriLabColor} allocation per instance; this is intentional and avoids
 * cross-instance count pollution.
 * </p>
 * <p>
 * Internally colours are represented as {@link TriElm} triples of {@code short}
 * values. RGB components are pre-multiplied by alpha so that every stored value
 * represents what the human eye would perceive over a black background. CIELab
 * components are stored scaled by 100 and rounded to {@code short}: L* in [0,
 * 10 000], a* and b* in [−12 800, 12 800].
 * </p>
 * <p>
 * The primary entry point for image analysis is the {@link ColorImage} class,
 * which reads an image file, populates a dedicated {@code ColorBase}, and
 * replaces each raw pixel value with its alpha-premultiplied absolute RGB
 * counterpart as returned by {@link #add(int)}.
 * </p>
 */
public class ColorBase {

    /**
     * Static, cross-instance RGB → CIELab cache.
     * <p>
     * Populated whenever a colour is computed for the first time in any
     * {@code ColorBase} instance. Allows a fresh instance to benefit from
     * conversions already performed by earlier instances without carrying the
     * full per-instance cache.
     * </p>
     * <p>
     * Shared across every {@code ColorBase} instance and thread in the JVM, so
     * every touch of this map — here and in {@link #reverse} — takes the
     * {@code ColorBase.class} monitor via a {@code synchronized (ColorBase.class)}
     * block: in {@link #resolve}, {@link #resolveFromLab}, and
     * {@link TriLabColor#TriLabColor(ColorBase, Color)}. {@link #saveConvert}
     * and {@link #loadConvert} are {@code synchronized static} methods instead
     * (locking the same monitor for their whole body), since they iterate the
     * map and need the lock held for the full walk, not just single calls. A
     * plain {@link TreeMap} has no locking of its own; concurrent structural
     * modification (a {@code put} of a new key) without this would corrupt the
     * tree for any other thread mid-walk, not just race on a value — so the
     * lock only ever wraps the cheap map lookup/insert itself, never the CIELab
     * math on either side of it, keeping each critical section down to a
     * handful of nanoseconds regardless of cache hit or miss.
     * </p>
     */
    public static TreeMap<TriElm, TriLabColor> convert = new TreeMap<>();

    /**
     * Static, cross-instance LAB → RGB reverse cache.
     * <p>
     * Keyed by a {@link TriElm} with {@code v0=L*x100}, {@code v1=a*x100},
     * {@code v2=b*x100} (all as {@code short}). Populated whenever a LAB-to-RGB
     * conversion is performed for the first time. Avoids repeated FPU work for
     * the same LAB coordinates: doubles entering {@code resolveFromLab} are
     * rounded to the same scaled-short triple before the lookup, so distinct
     * IEEE representations that map to the same perceptual point share one
     * cache entry.
     * </p>
     */
    public static TreeMap<TriElm, TriLabColor> reverse = new TreeMap<>();

    /**
     * Saves the static {@link #convert} cache to a file for use across JVM
     * sessions.
     * <p>
     * Each {@link TriLabColor} entry is written as six {@code short} values
     * (v0, v1, v2, l, a, b) and one {@code long} (count), totalling 20 bytes
     * per entry. The file begins with an {@code int} entry count. Callers on
     * fast local storage (NVMe) will find this worthwhile; on network or slow
     * storage the call is still correct but may not pay for itself.
     * </p>
     * <p>
     * This method is a no-op if {@code f} is {@code null}.
     * </p>
     *
     * @param f the file to write; created or overwritten
     * @throws IOException if the file cannot be written
     */
    public static synchronized void saveConvert(File f) throws IOException {
        if (null == f) {
            return;
        }
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(
                        new java.io.FileOutputStream(f)))) {
            out.writeInt(convert.size());
            for (TriLabColor tc : convert.values()) {
                out.writeShort(tc.v0);
                out.writeShort(tc.v1);
                out.writeShort(tc.v2);
                out.writeShort(tc.l);
                out.writeShort(tc.a);
                out.writeShort(tc.b);
                out.writeLong(tc.count);
            }
            // Reverse cache: entries whose RGB key is absent from convert
            // (LAB-originated entries not yet seen from the RGB side).
            int reverseOnly = 0;
            for (TriElm labKey : reverse.keySet()) {
                TriLabColor tc = reverse.get(labKey);
                if (!convert.containsKey(tc)) {
                    reverseOnly++;
                }
            }
            out.writeInt(reverseOnly);
            for (TriElm labKey : reverse.keySet()) {
                TriLabColor tc = reverse.get(labKey);
                if (!convert.containsKey(tc)) {
                    out.writeShort(labKey.v0);
                    out.writeShort(labKey.v1);
                    out.writeShort(labKey.v2);
                    out.writeShort(tc.v0);
                    out.writeShort(tc.v1);
                    out.writeShort(tc.v2);
                    out.writeLong(tc.count);
                }
            }
        }
    }

    /**
     * Loads previously saved {@link #convert} and {@link #reverse} caches from
     * a file, merging entries into the current static caches.
     * <p>
     * Existing entries are not overwritten. Load order is irrelevant and the
     * call is safe at any point in the session.
     * </p>
     * <p>
     * This method is a no-op if {@code f} is {@code null} or does not exist.
     * </p>
     *
     * @param f the file to read; must have been written by {@link #saveConvert}
     * @throws IOException if the file cannot be read or is malformed
     */
    public static synchronized void loadConvert(File f) throws IOException {
        if (null == f || !f.exists()) {
            return;
        }
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(
                        new java.io.FileInputStream(f)))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                TriLabColor tc = new TriLabColor();
                tc.v0 = in.readShort();
                tc.v1 = in.readShort();
                tc.v2 = in.readShort();
                tc.l = in.readShort();
                tc.a = in.readShort();
                tc.b = in.readShort();
                tc.count = in.readLong();
                TriLabColor existing = convert.putIfAbsent(tc, tc);
                // Rebuild reverse entry regardless; putIfAbsent returns null on insert.
                TriLabColor canonical = (null == existing) ? tc : existing;
                TriElm labKey = new TriElm();
                labKey.v0 = canonical.l;
                labKey.v1 = canonical.a;
                labKey.v2 = canonical.b;
                reverse.putIfAbsent(labKey, canonical);
            }
            int reverseOnly = in.readInt();
            for (int i = 0; i < reverseOnly; i++) {
                TriElm labKey = new TriElm();
                labKey.v0 = in.readShort();
                labKey.v1 = in.readShort();
                labKey.v2 = in.readShort();
                TriLabColor tc = new TriLabColor();
                tc.v0 = in.readShort();
                tc.v1 = in.readShort();
                tc.v2 = in.readShort();
                tc.l = labKey.v0;
                tc.a = labKey.v1;
                tc.b = labKey.v2;
                tc.count = in.readLong();
                reverse.putIfAbsent(labKey, tc);
                convert.putIfAbsent(tc, tc);
            }
        }
    }

    /**
     * Instance-level RGB → CIELab cache.
     * <p>
     * Keyed and valued by the same {@link TriLabColor} object so that the map
     * serves as both a set-membership test and a value store.
     * </p>
     */
    public TreeMap<TriElm, TriLabColor> cache = new TreeMap<>();
    private final TriElm scratch = new TriElm();

    /**
     * Registers a raw ARGB pixel value and returns its alpha-premultiplied
     * absolute RGB.
     * <p>
     * The method is synchronised on {@link #scratch} to prevent concurrent
     * corruption of the shared mutable key object. Within a single-threaded
     * caller this has no observable effect; it is retained as a guard against
     * accidental future multi-threaded use.
     * </p>
     * <p>
     * On first encounter the colour is looked up in {@link #cache} (instance
     * level), then in {@link #convert} (static level), and — on a complete miss
     * — fully converted to CIELab and registered in both caches via
     * {@link TriLabColor#TriLabColor(ColorBase, Color)}. On subsequent
     * encounters only the instance-level {@link TriLabColor#count} is
     * incremented.
     * </p>
     *
     * @param rgb a 32-bit ARGB value as returned by
     * {@link java.awt.image.BufferedImage#getRGB}
     * @return the alpha-premultiplied absolute RGB value: the red component in
     * bits 23–16, green in bits 15–8, blue in bits 7–0, with the alpha channel
     * cleared
     */
    public int add(int rgb) {
        synchronized (scratch) {
            int al = (rgb >>> 24) & 0xFF;
            if (al < 255) {
                scratch.v0 = (short) (((rgb >> 16) & 0xFF) * al / 255);
                scratch.v1 = (short) (((rgb >> 8) & 0xFF) * al / 255);
                scratch.v2 = (short) ((rgb & 0xFF) * al / 255);
            } else {
                scratch.v0 = (short) ((rgb >> 16) & 0xFF);
                scratch.v1 = (short) ((rgb >> 8) & 0xFF);
                scratch.v2 = (short) (rgb & 0xFF);
            }
            TriLabColor lu = cache.get(scratch);
            if (null != lu) {
                lu.count++;
            } else {
                new TriLabColor(this, new Color(rgb, true));
            }
            return (scratch.v0 << 16) | (scratch.v1 << 8) | scratch.v2;

        }
    }

    /**
     * Registers a {@link Color} and returns its alpha-premultiplied absolute
     * RGB.
     * <p>
     * Behaves identically to {@link #add(int)} but accepts a {@link Color}
     * directly, avoiding the intermediate ARGB packing and unpacking. The
     * method is synchronised on {@link #scratch} for the same defensive reasons
     * as {@link #add(int)}.
     * </p>
     *
     * @param c the colour to register; alpha values below 255 are
     * pre-multiplied against the RGB channels before caching
     * @return the alpha-premultiplied absolute RGB value: the red component in
     * bits 23–16, green in bits 15–8, blue in bits 7–0, with the alpha channel
     * cleared
     */
    public int add(Color c) {
        synchronized (scratch) {
            int al = c.getAlpha();
            if (al < 255) {
                scratch.v0 = (short) (c.getRed() * al / 255);
                scratch.v1 = (short) (c.getGreen() * al / 255);
                scratch.v2 = (short) (c.getBlue() * al / 255);
            } else {
                scratch.v0 = (short) c.getRed();
                scratch.v1 = (short) c.getGreen();
                scratch.v2 = (short) c.getBlue();
            }
            TriLabColor lu = cache.get(scratch);
            if (null != lu) {
                lu.count++;
            } else {
                new TriLabColor(this, c);
            }
            return (scratch.v0 << 16) | (scratch.v1 << 8) | scratch.v2;

        }
    }

    /**
     * Resolves a {@link Color} to its CIELab triple, using and populating the
     * static {@link #convert} cache without touching any instance cache or
     * pixel counts.
     * <p>
     * This is the shared back-end for all {@code deltaE} overloads. It
     * guarantees that every colour seen through {@code deltaE} is cached for
     * future conversions while keeping {@link TriLabColor#count} and the
     * instance {@link #cache} completely untouched.
     * </p>
     *
     * Also usable directly by callers (e.g. {@link ColorImage}'s thumbnail
     * conversion) that want a one-off RGB→CIELab lookup through the shared
     * static cache without creating or touching any {@code ColorBase}
     * instance.
     *
     * @param c the colour to resolve; alpha values below 255 are pre-multiplied
     * before the cache lookup
     * @return a {@link TriLabColor} with valid {@code l}, {@code a}, {@code b}
     * fields; may be a pre-existing cached instance or a freshly computed one
     */
    public static TriLabColor resolve(Color c) {
        TriElm key = new TriElm();
        int al = c.getAlpha();
        if (al < 255) {
            key.v0 = (short) (c.getRed() * al / 255);
            key.v1 = (short) (c.getGreen() * al / 255);
            key.v2 = (short) (c.getBlue() * al / 255);
        } else {
            key.v0 = (short) c.getRed();
            key.v1 = (short) c.getGreen();
            key.v2 = (short) c.getBlue();
        }
        TriLabColor lu;
        synchronized (ColorBase.class) {
            lu = convert.get(key);
        }
        if (null != lu) {
            return lu;
        }
        double[] cielab = EnhancedColor.getCIELAB(c);
        TriLabColor tc = new TriLabColor();
        tc.v0 = key.v0;
        tc.v1 = key.v1;
        tc.v2 = key.v2;
        tc.l = (short) Math.max(0, Math.min(10000, cielab[0]));
        tc.a = (short) Math.max(-12800, Math.min(12800, cielab[1]));
        tc.b = (short) Math.max(-12800, Math.min(12800, cielab[2]));
        synchronized (ColorBase.class) {
            convert.put(tc, tc);
        }
        return tc;
    }

    /**
     * Computes the CIE76 ΔE between this instance's colour and another
     * {@link TriLabColor}.
     * <p>
     * CIE76: {@code sqrt((ΔL*)² + (Δa*)² + (Δb*)²)} where components are the
     * unscaled LAB values (divided by 100 before the calculation).
     * </p>
     *
     * @param other the colour to compare against
     * @return the CIE76 colour difference; 0 = identical, ~2.3 = just
     * noticeable difference under D65 illuminant
     */
    public static double deltaE(TriLabColor self, TriLabColor other) {
        double dl = (self.l - other.l) / 100.0;
        double da = (self.a - other.a) / 100.0;
        double db = (self.b - other.b) / 100.0;
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    /**
     * Computes the CIE76 ΔE between a {@link TriLabColor} and an AWT
     * {@link Color}.
     * <p>
     * The {@link Color} is resolved through the static {@link #convert} cache
     * (computed and cached if not already present) without affecting any
     * instance cache or pixel counts.
     * </p>
     *
     * @param self the reference colour
     * @param other the AWT colour to compare against
     * @return the CIE76 colour difference
     */
    public static double deltaE(TriLabColor self, Color other) {
        return deltaE(self, resolve(other));
    }

    /**
     * Computes the CIE76 ΔE between a {@link TriLabColor} and a raw ARGB
     * {@code int} as returned by {@link BufferedImage#getRGB}.
     * <p>
     * The integer is wrapped in an AWT {@link Color} (alpha-aware) and resolved
     * through the static {@link #convert} cache without affecting any instance
     * cache or pixel counts.
     * </p>
     *
     * @param self the reference colour
     * @param argb a 32-bit ARGB pixel value
     * @return the CIE76 colour difference
     */
    public static double deltaE(TriLabColor self, int argb) {
        return deltaE(self, resolve(new Color(argb, true)));
    }

    /**
     * Computes the CIE76 ΔE between two AWT {@link Color} instances, caching
     * both through {@link #convert}.
     * <p>
     * Equivalent to calling {@link EnhancedColor#deltaE(Color, Color)} but with
     * full two-level caching: neither conversion will be repeated for either
     * colour within this JVM session.
     * </p>
     *
     * @param c1 first colour
     * @param c2 second colour
     * @return the CIE76 colour difference
     */
    public static double deltaE(Color c1, Color c2) {
        return deltaE(resolve(c1), resolve(c2));
    }

    /**
     * Resolves unscaled CIELab coordinates to a cached {@link TriLabColor},
     * hitting the FPU only on a complete cache miss.
     * <p>
     * Lookup order:
     * </p>
     * <ol>
     * <li>Round L, a, b to their scaled-short representation and probe
     * {@link #reverse} (LAB-keyed). A hit returns immediately — no floating
     * point work at all. Two doubles that round to the same scaled triple are
     * treated as the same colour, which is correct: the difference is below the
     * resolution of the stored representation.</li>
     * <li>On a miss, call {@link EnhancedColor#fromCIELAB} (FPU), build the
     * {@link TriLabColor}, and register it in both {@link #reverse} (LAB key)
     * and {@link #convert} (RGB key).</li>
     * </ol>
     *
     * @param L unscaled L* (0..100)
     * @param a unscaled a* (typically -128..+127)
     * @param b unscaled b* (typically -128..+127)
     * @return cached or freshly computed {@link TriLabColor}
     */
    private static TriLabColor resolveFromLab(double L, double a, double b) {
        TriElm labKey = new TriElm();
        labKey.v0 = (short) Math.max(0, Math.min(10000, Math.round(L * 100)));
        labKey.v1 = (short) Math.max(-12800, Math.min(12800, Math.round(a * 100)));
        labKey.v2 = (short) Math.max(-12800, Math.min(12800, Math.round(b * 100)));
        TriLabColor lu;
        synchronized (ColorBase.class) {
            lu = reverse.get(labKey);
        }
        if (null != lu) {
            return lu;
        }
        EnhancedColor ec = EnhancedColor.fromCIELAB(L, a, b);
        TriLabColor tc = new TriLabColor();
        tc.v0 = (short) ec.getRed();
        tc.v1 = (short) ec.getGreen();
        tc.v2 = (short) ec.getBlue();
        tc.l = labKey.v0;
        tc.a = labKey.v1;
        tc.b = labKey.v2;
        synchronized (ColorBase.class) {
            reverse.put(labKey, tc);
            convert.putIfAbsent(tc, tc);
        }
        return tc;
    }

    /**
     * Converts unscaled CIELab coordinates to an {@link EnhancedColor}, caching
     * the result in {@link #convert} so the underlying RGB↔LAB conversion is
     * never repeated.
     *
     * @param L unscaled L* (0..100)
     * @param a unscaled a* (typically -128..+127)
     * @param b unscaled b* (typically -128..+127)
     * @return the corresponding {@link EnhancedColor}
     */
    public static EnhancedColor lab2Color(double L, double a, double b) {
        TriLabColor tc = resolveFromLab(L, a, b);
        return new EnhancedColor(tc.v0, tc.v1, tc.v2);
    }

    /**
     * Converts unscaled CIELab coordinates to a {@link TriLabColor}, caching
     * the result in {@link #convert}.
     * <p>
     * The returned object carries both the RGB key ({@code v0/v1/v2}) and the
     * scaled LAB fields ({@code l/a/b}), and is ready for ΔE comparisons via
     * {@link #deltaE(TriLabColor, TriLabColor)}.
     * </p>
     *
     * @param L unscaled L* (0..100)
     * @param a unscaled a* (typically -128..+127)
     * @param b unscaled b* (typically -128..+127)
     * @return cached or freshly computed {@link TriLabColor}
     */
    public static TriLabColor lab2TriLabColor(double L, double a, double b) {
        return resolveFromLab(L, a, b);
    }

    /**
     * Converts unscaled CIELab coordinates to a {@link TriElm} RGB key, caching
     * the full {@link TriLabColor} in {@link #convert} as a side effect.
     * <p>
     * Use when only the RGB key is needed for a map lookup and the LAB fields
     * are not required by the caller.
     * </p>
     *
     * @param L unscaled L* (0..100)
     * @param a unscaled a* (typically -128..+127)
     * @param b unscaled b* (typically -128..+127)
     * @return a {@link TriElm} with {@code v0/v1/v2} set to the RGB components
     * of the converted colour
     */
    public static TriElm lab2TriElm(double L, double a, double b) {
        return resolveFromLab(L, a, b);
    }

    /**
     * A colour triple that extends {@link TriElm} with CIELab components and an
     * instance-level hit counter.
     * <p>
     * {@code v0/v1/v2} (inherited) hold the alpha-premultiplied RGB values and
     * serve as the map key. {@code l}, {@code a}, {@code b} hold the
     * corresponding CIELab values scaled by 100:
     * </p>
     * <ul>
     * <li>L* × 100 → stored in {@link #l}, clamped to [0, 10 000].</li>
     * <li>a* × 100 → stored in {@link #a}, clamped to [−12 800, 12 800].</li>
     * <li>b* × 100 → stored in {@link #b}, clamped to [−12 800, 12 800].</li>
     * </ul>
     * <p>
     * The {@link #count} field tracks how many times this particular RGB value
     * has been seen via {@link ColorBase#add} within the owning instance. It
     * has no meaning in the static {@link ColorBase#convert} cache.
     * </p>
     */
    public static class TriLabColor extends TriElm {

        /**
         * CIELab L* × 100, clamped to [0, 10 000].
         */
        public short l;
        /**
         * CIELab a* × 100, clamped to [−12 800, 12 800].
         */
        public short a;
        /**
         * CIELab b* × 100, clamped to [−12 800, 12 800].
         */
        public short b;

        /**
         * Number of times this colour has been seen through
         * {@link ColorBase#add} on the owning instance. Starts at 1 on
         * construction (the first occurrence). Subsequent occurrences are
         * counted by the early-return path in {@code add()}, not by this
         * constructor. Meaningful only within a single {@link ColorBase}
         * instance; not tracked in {@link ColorBase#convert}.
         */
        public long count = 1;

        /**
         * Default no-arg constructor; all fields left at their Java defaults.
         * Used when constructing a key object before a cache lookup.
         */
        public TriLabColor() {
        }

        /**
         * Constructs a fully initialised {@code TriLabColor} from a
         * {@link Color}, using the two-level cache to avoid redundant CIELab
         * conversions.
         * <p>
         * This constructor is only reached from {@code add()} when the colour
         * is absent from the instance cache; the instance-cache check inside
         * the constructor is therefore always a miss when called via
         * {@code add()}, but is retained for safety if the constructor is ever
         * called directly.
         * </p>
         * <p>
         * Construction proceeds as follows:
         * </p>
         * <ol>
         * <li>Alpha-premultiply the RGB components and store them in
         * {@code v0/v1/v2} so that {@code this} can act as a lookup key.</li>
         * <li>Check {@code cb.cache} (instance level). On hit, increment the
         * cached object's {@link #count} and copy its LAB values (direct
         * construction path only; never reached via {@code add()}).</li>
         * <li>On miss, check {@link ColorBase#convert} (static level). On hit,
         * copy the LAB values. {@code this} is NOT inserted into
         * {@code cb.cache} here; the caller ({@code add()}) never reaches this
         * constructor for a colour already in the instance cache, so the
         * absence of a put on the static-hit path is intentional — it trades
         * one extra allocation on cross-image colour collisions for simpler,
         * correct accounting.</li>
         * <li>On miss in both caches, compute CIELab via
         * {@link EnhancedColor#getCIELAB(Color)}, clamp and store the results,
         * then register {@code this} in both caches.</li>
         * </ol>
         *
         * @param cb the owning {@link ColorBase} instance whose instance cache
         * is consulted and updated
         * @param c the source {@link Color}; alpha values below 255 are
         * pre-multiplied against the RGB channels
         */
        public TriLabColor(ColorBase cb, Color c) {
            int al = c.getAlpha();
            if (al < 255) {
                // Pre-multiply alpha; result is what the eye would see over black.
                v0 = (short) (c.getRed() * al / 255);
                v1 = (short) (c.getGreen() * al / 255);
                v2 = (short) (c.getBlue() * al / 255);
            } else {
                v0 = (short) c.getRed();
                v1 = (short) c.getGreen();
                v2 = (short) c.getBlue();
            }
            TriLabColor lu = cb.cache.get(this);
            if (null == lu) {
                synchronized (ColorBase.class) {
                    lu = convert.get(this);
                }
            } else {
                lu.count++;
            }
            if (null == lu) {
                double[] cielab = EnhancedColor.getCIELAB(c);
                l = (short) Math.max(0, Math.min(10000, cielab[0]));
                a = (short) Math.max(-12800, Math.min(12800, cielab[1]));
                b = (short) Math.max(-12800, Math.min(12800, cielab[2]));
                cb.cache.put(this, this);
                synchronized (ColorBase.class) {
                    convert.put(this, this);
                }
            } else {
                l = lu.l;
                a = lu.a;
                b = lu.b;
            }
        }
    }

}
