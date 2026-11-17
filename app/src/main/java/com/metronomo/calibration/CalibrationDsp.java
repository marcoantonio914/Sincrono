package com.metronomo.calibration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Processamento da calibração automática (Java puro, sem dependência do Android).
 *
 * Cada aparelho toca um "chirp" (varredura de 2 a 6 kHz, 40 ms) em horários combinados.
 * Um aparelho grava tudo pelo microfone; aqui medimos, por correlação com o chirp, o
 * instante exato em que cada som chegou, e comparamos cada aparelho com o próprio
 * aparelho que gravou. Como todos passam pelo mesmo microfone, a latência do
 * microfone e a distância comum cancelam na diferença.
 *
 * Ordem dos horários (slots): são repeats+1 rodadas; em cada rodada r, o aparelho d toca no
 * slot r*N+d. Assim, para qualquer aparelho que grave, o som de cada outro aparelho fica entre
 * dois sons do próprio gravador, e a deriva do relógio da gravação é removida por interpolação
 * linear. Várias gravações (de vários aparelhos) são combinadas por mínimos quadrados em solve().
 */
public final class CalibrationDsp {
    private CalibrationDsp() {}

    public static final double F0 = 2000.0;
    public static final double F1 = 6000.0;
    public static final double DURATION = 0.04;

    /** Razão mínima entre o pico e o nível médio da janela para aceitar uma detecção. */
    public static final double MIN_RATIO = 7.0;
    /** Diferenças maiores que isso são tratadas como erro de medição. */
    public static final double MAX_ABS_OFFSET_MS = 150.0;

    private static final int COARSE_STRIDE = 8;

    private static double phase(double t) {
        return 2.0 * Math.PI * (F0 * t + (F1 - F0) / (2.0 * DURATION) * t * t);
    }

    private static double window(double t) {
        return 0.5 * (1.0 - Math.cos(2.0 * Math.PI * t / DURATION));
    }

    /** Valor do chirp no instante t (s) desde o início do som; 0 fora da duração. */
    public static double chirpSample(double t, double amplitude) {
        if (t < 0.0 || t >= DURATION) return 0.0;
        return amplitude * window(t) * Math.sin(phase(t));
    }

    /** Chirp pronto para tocar (PCM 16 bits mono) na taxa informada. */
    public static short[] chirpPcm(int sampleRate, double amplitude) {
        int n = (int) Math.round(DURATION * sampleRate);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double v = chirpSample(i / (double) sampleRate, amplitude);
            out[i] = (short) Math.round(Math.max(-1.0, Math.min(1.0, v)) * 32767.0);
        }
        return out;
    }

    /** Resultado da análise. Índice 0 é o aparelho que gravou (referência, sempre 0 ms). */
    public static final class Analysis {
        public final boolean ok;
        public final String error;
        /** Quanto cada aparelho soa DEPOIS do aparelho 0, em ms (NaN = não medido). */
        public final double[] offsetsMs;
        /** Diferença entre a maior e a menor medição de cada aparelho, em ms. */
        public final double[] spreadMs;
        /** Quantas das rodadas foram usadas para cada aparelho. */
        public final int[] counts;

        Analysis(boolean ok, String error, double[] offsetsMs, double[] spreadMs, int[] counts) {
            this.ok = ok;
            this.error = error;
            this.offsetsMs = offsetsMs;
            this.spreadMs = spreadMs;
            this.counts = counts;
        }

        static Analysis failure(String message, int devices) {
            double[] nan = new double[devices];
            Arrays.fill(nan, Double.NaN);
            return new Analysis(false, message, nan, nan.clone(), new int[devices]);
        }
    }

    /**
     * Analisa a gravação feita pelo aparelho "recorderIndex".
     *
     * Ordem dos horários (slots): são repeats+1 rodadas; em cada rodada r, o aparelho d toca
     * no slot r*N+d. Assim, para qualquer gravador k, o som de cada outro aparelho fica entre
     * dois sons do próprio gravador, e a deriva do relógio da gravação sai por interpolação.
     *
     * Resultado: quanto cada aparelho soa DEPOIS do gravador (o gravador é a referência, 0 ms).
     *
     * @param rec                    gravação mono, 16 bits
     * @param length                 quantidade válida de amostras em rec
     * @param sampleRate             taxa da gravação
     * @param estimatedFirstSample   posição aproximada (amostras) do som do slot 0
     * @param slotSamples            distância entre slots, em amostras
     * @param devices                número de aparelhos (incluindo o gravador)
     * @param repeats                medições por aparelho (rodadas = repeats + 1)
     * @param recorderIndex          índice do aparelho que gravou
     */
    public static Analysis analyze(short[] rec, int length, int sampleRate, double estimatedFirstSample,
                                   int slotSamples, int devices, int repeats, int recorderIndex) {
        return analyze(rec, length, sampleRate, estimatedFirstSample, slotSamples, devices, repeats, recorderIndex, false);
    }

    /**
     * Igual a analyze(...), com a opção [cable]: no modo cabo todos os aparelhos chegam ao gravador
     * com níveis parecidos (não há "o próprio alto-falante, bem mais forte"), então o som do
     * próprio gravador não pode ser achado pelo mais forte numa janela larga: ele é procurado
     * só perto do horário previsto, numa janela menor que meio slot, onde os vizinhos não entram.
     */
    public static Analysis analyze(short[] rec, int length, int sampleRate, double estimatedFirstSample,
                                   int slotSamples, int devices, int repeats, int recorderIndex, boolean cable) {
        if (devices < 2 || repeats < 1 || recorderIndex < 0 || recorderIndex >= devices) {
            return Analysis.failure("Parâmetros inválidos.", Math.max(devices, 1));
        }
        final int k = recorderIndex;
        float[] x = new float[length];
        for (int i = 0; i < length; i++) x[i] = rec[i] / 32768f;

        int n = (int) Math.round(DURATION * sampleRate);
        float[] s = new float[n];
        float[] q = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) sampleRate;
            double w = window(t);
            s[i] = (float) (w * Math.sin(phase(t)));
            q[i] = (float) (w * Math.cos(phase(t)));
        }

        int rounds = repeats + 1;
        int slots = rounds * devices;

        int firstHalf = cable ? Math.min((int) (0.28 * sampleRate), (int) (0.46 * slotSamples)) : (int) (0.8 * sampleRate);
        double first = findArrival(x, length, s, q, estimatedFirstSample + (double) k * slotSamples, firstHalf);
        if (Double.isNaN(first)) {
            return Analysis.failure(cable
                    ? "Não consegui ouvir a saída deste próprio aparelho pela mesa. Ele também precisa estar ligado a um canal "
                    + "da mesa pela saída de fone, e a saída da mesa precisa chegar à entrada dele. Confira os cabos e o nível."
                    : "Não consegui ouvir o alto-falante deste próprio aparelho. "
                    + "Aumente o volume, fique em silêncio e confira a permissão do microfone.", devices);
        }

        int searchHalf = (int) (0.25 * sampleRate);
        double[] arrival = new double[slots];
        Arrays.fill(arrival, Double.NaN);
        arrival[k] = first;
        for (int j = 0; j < slots; j++) {
            if (j == k) continue;
            arrival[j] = findArrival(x, length, s, q, first + (double) (j - k) * slotSamples, searchHalf);
        }

        double[] own = new double[rounds];
        int ownFound = 0;
        for (int r = 0; r < rounds; r++) {
            own[r] = arrival[r * devices + k];
            if (!Double.isNaN(own[r])) ownFound++;
        }
        if (ownFound < 3) {
            return Analysis.failure("Ouvi poucos sons do próprio aparelho que está gravando. "
                    + "Aumente o volume e tente em um lugar mais silencioso.", devices);
        }

        double[] offsets = new double[devices];
        double[] spread = new double[devices];
        int[] counts = new int[devices];
        Arrays.fill(offsets, Double.NaN);
        Arrays.fill(spread, Double.NaN);
        offsets[k] = 0.0;
        spread[k] = 0.0;
        counts[k] = ownFound;

        for (int d = 0; d < devices; d++) {
            if (d == k) continue;
            List<Double> diffs = new ArrayList<>();
            for (int m = 0; m < repeats; m++) {
                int r0;       // índice do som do gravador que vem ANTES do som do aparelho d
                double frac;  // posição relativa entre os dois sons do gravador
                int slot;
                if (d > k) {
                    r0 = m;
                    slot = m * devices + d;
                    frac = (double) (d - k) / devices;
                } else {
                    r0 = m;
                    slot = (m + 1) * devices + d;
                    frac = (double) (devices + d - k) / devices;
                }
                double a = arrival[slot];
                double h0 = own[r0];
                double h1 = own[r0 + 1];
                if (Double.isNaN(a) || Double.isNaN(h0) || Double.isNaN(h1)) continue;
                double expectedOwn = h0 + (h1 - h0) * frac;
                diffs.add((a - expectedOwn) / sampleRate * 1000.0);
            }
            counts[d] = diffs.size();
            if (diffs.size() < 2) continue;
            double med = median(diffs);
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (double dd : diffs) {
                min = Math.min(min, dd);
                max = Math.max(max, dd);
            }
            if (Math.abs(med) > MAX_ABS_OFFSET_MS) continue;
            offsets[d] = med;
            spread[d] = max - min;
        }
        return new Analysis(true, "", offsets, spread, counts);
    }

    /**
     * Confiança de um resultado de calibração: "high", "medium" ou "low". [spreadMs] é a maior
     * variação entre repetições e entre gravadores; [minCount] é o menor número de repetições
     * aproveitadas numa das ligações que envolvem o aparelho.
     */
    public static String confidence(double spreadMs, int minCount) {
        if (Double.isNaN(spreadMs) || minCount < 2) return "low";
        if (spreadMs <= 1.5 && minCount >= 3) return "high";
        if (spreadMs <= 4.0) return "medium";
        return "low";
    }

    /** Uma medição de um gravador: "o aparelho device soa offsetMs depois do aparelho recorder". */
    public static final class Measurement {
        public final int recorder;
        public final int device;
        public final double offsetMs;
        public final double spreadMs;
        public final int count;

        public Measurement(int recorder, int device, double offsetMs, double spreadMs, int count) {
            this.recorder = recorder;
            this.device = device;
            this.offsetMs = offsetMs;
            this.spreadMs = spreadMs;
            this.count = count;
        }
    }

    /** Resultado da combinação. O aparelho 0 é a referência (0 ms). NaN = não foi ouvido por ninguém. */
    public static final class Solution {
        public final double[] offsetsMs;
        /** Maior entre a variação das repetições e a discordância entre gravadores. */
        public final double[] spreadMs;
        /** Quantas medições (de todos os gravadores) envolvem o aparelho. */
        public final int[] links;

        Solution(double[] offsetsMs, double[] spreadMs, int[] links) {
            this.offsetsMs = offsetsMs;
            this.spreadMs = spreadMs;
            this.links = links;
        }
    }

    /**
     * Combina as medições de vários gravadores por mínimos quadrados ponderados:
     * para cada medição, offset[device] - offset[recorder] = offsetMs. O aparelho 0 fixa a
     * referência. Aparelhos sem nenhuma ligação (direta ou indireta) com o aparelho 0 ficam NaN.
     */
    public static Solution solve(int devices, List<Measurement> measurements) {
        double[] offsets = new double[devices];
        double[] spread = new double[devices];
        int[] links = new int[devices];
        Arrays.fill(offsets, Double.NaN);
        Arrays.fill(spread, Double.NaN);

        boolean[] reach = new boolean[devices];
        reach[0] = true;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Measurement m : measurements) {
                if (reach[m.recorder] != reach[m.device]) {
                    reach[m.recorder] = true;
                    reach[m.device] = true;
                    changed = true;
                }
            }
        }

        int[] idx = new int[devices];
        Arrays.fill(idx, -1);
        int n = 0;
        for (int i = 1; i < devices; i++) if (reach[i]) idx[i] = n++;

        double[] solution = new double[n];
        if (n > 0) {
            double[][] a = new double[n][n + 1];
            for (Measurement m : measurements) {
                if (!reach[m.recorder] || !reach[m.device]) continue;
                double w = 1.0 / Math.pow(0.3 + m.spreadMs, 2);
                int j = idx[m.device];
                int r = idx[m.recorder];
                if (j >= 0) {
                    a[j][j] += w;
                    a[j][n] += w * m.offsetMs;
                }
                if (r >= 0) {
                    a[r][r] += w;
                    a[r][n] -= w * m.offsetMs;
                }
                if (j >= 0 && r >= 0) {
                    a[j][r] -= w;
                    a[r][j] -= w;
                }
            }
            for (int col = 0; col < n; col++) {
                int pivot = col;
                for (int row = col + 1; row < n; row++) {
                    if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) pivot = row;
                }
                double[] tmp = a[col];
                a[col] = a[pivot];
                a[pivot] = tmp;
                double div = a[col][col];
                if (Math.abs(div) < 1e-12) continue;
                for (int c = col; c <= n; c++) a[col][c] /= div;
                for (int row = 0; row < n; row++) {
                    if (row == col) continue;
                    double factor = a[row][col];
                    if (factor == 0.0) continue;
                    for (int c = col; c <= n; c++) a[row][c] -= factor * a[col][c];
                }
            }
            for (int i = 0; i < n; i++) solution[i] = a[i][n];
        }

        offsets[0] = 0.0;
        for (int i = 1; i < devices; i++) if (reach[i]) offsets[i] = solution[idx[i]];

        double[] worst = new double[devices];
        for (Measurement m : measurements) {
            if (!reach[m.recorder] || !reach[m.device]) continue;
            double residual = Math.abs((offsets[m.device] - offsets[m.recorder]) - m.offsetMs);
            double bad = Math.max(residual, m.spreadMs);
            worst[m.device] = Math.max(worst[m.device], bad);
            worst[m.recorder] = Math.max(worst[m.recorder], bad);
            links[m.device]++;
            links[m.recorder]++;
        }
        for (int i = 0; i < devices; i++) if (reach[i]) spread[i] = worst[i];
        return new Solution(offsets, spread, links);
    }

    private static double median(List<Double> values) {
        double[] a = new double[values.size()];
        for (int i = 0; i < a.length; i++) a[i] = values.get(i);
        Arrays.sort(a);
        int m = a.length / 2;
        return a.length % 2 == 1 ? a[m] : 0.5 * (a[m - 1] + a[m]);
    }

    private static double envelope(float[] x, int n, float[] s, float[] q) {
        double a = 0.0;
        double b = 0.0;
        for (int k = 0; k < s.length; k++) {
            float v = x[n + k];
            a += v * s[k];
            b += v * q[k];
        }
        return Math.sqrt(a * a + b * b);
    }

    /**
     * Procura o pico da correlação com o chirp perto de "center" (amostras), em +-half.
     * Devolve a posição fracionária (amostras) do início do som, ou NaN se não houver pico claro.
     */
    static double findArrival(float[] x, int length, float[] s, float[] q, double center, int half) {
        int len = s.length;
        int lo = (int) Math.max(0, Math.floor(center) - half);
        int hi = (int) Math.min(length - len - 1, Math.floor(center) + half);
        if (hi - lo < 2 * COARSE_STRIDE) return Double.NaN;

        double best = -1.0;
        double sum = 0.0;
        int count = 0;
        int bestN = lo;
        for (int n = lo; n <= hi; n += COARSE_STRIDE) {
            double e = envelope(x, n, s, q);
            sum += e;
            count++;
            if (e > best) {
                best = e;
                bestN = n;
            }
        }
        double mean = sum / count;
        if (mean <= 0.0 || best / mean < MIN_RATIO) return Double.NaN;

        int fl = Math.max(lo, bestN - COARSE_STRIDE - 2);
        int fh = Math.min(hi, bestN + COARSE_STRIDE + 2);
        double fbest = -1.0;
        int fn = fl;
        for (int n = fl; n <= fh; n++) {
            double e = envelope(x, n, s, q);
            if (e > fbest) {
                fbest = e;
                fn = n;
            }
        }
        if (fn - 1 < 0 || fn + 1 > length - len) return fn;
        double e0 = envelope(x, fn - 1, s, q);
        double e2 = envelope(x, fn + 1, s, q);
        double denom = e0 - 2.0 * fbest + e2;
        double delta = denom != 0.0 ? 0.5 * (e0 - e2) / denom : 0.0;
        if (delta > 1.0) delta = 1.0;
        if (delta < -1.0) delta = -1.0;
        return fn + delta;
    }
}
