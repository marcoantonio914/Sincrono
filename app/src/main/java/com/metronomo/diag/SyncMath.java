package com.metronomo.diag;

import java.util.Arrays;

/**
 * Estatísticas e nível de qualidade da sincronização de relógio (Java puro, testável na JVM).
 *
 * Importante: a incerteza calculada aqui só enxerga o que varia (ruído/jitter). Uma assimetria
 * CONSTANTE entre ida e volta da rede (por exemplo, o roteador atrasa mais um sentido) gera um
 * erro fixo que nenhuma medição por troca de mensagens consegue ver. Por isso o valor mostrado
 * é "precisão estimada", não uma garantia.
 */
public final class SyncMath {
    private SyncMath() {}

    public enum Level { EXCELLENT, ACCEPTABLE, DEGRADED, UNRELIABLE }

    public static final class Stats {
        public final int count;
        public final double rttMin;
        public final double rttMedian;
        public final double rttLast;
        /** Desvio médio absoluto do RTT em torno da mediana (ms). */
        public final double jitter;
        /** Dispersão (desvio padrão) do offset entre as melhores amostras (RTT abaixo da mediana). */
        public final double offsetScatter;
        /** Precisão estimada do relógio (ms): a maior entre a dispersão medida e pisos proporcionais ao jitter e ao RTT. */
        public final double precision;

        Stats(int count, double rttMin, double rttMedian, double rttLast, double jitter, double offsetScatter, double precision) {
            this.count = count;
            this.rttMin = rttMin;
            this.rttMedian = rttMedian;
            this.rttLast = rttLast;
            this.jitter = jitter;
            this.offsetScatter = offsetScatter;
            this.precision = precision;
        }
    }

    /** [rtt] e [offset] em ordem cronológica, [n] amostras válidas. Devolve null se n == 0. */
    public static Stats stats(double[] rtt, double[] offset, int n) {
        if (n <= 0) return null;
        double[] sorted = Arrays.copyOf(rtt, n);
        Arrays.sort(sorted);
        double min = sorted[0];
        double median = n % 2 == 1 ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
        double dev = 0.0;
        for (int i = 0; i < n; i++) dev += Math.abs(rtt[i] - median);
        double jitter = dev / n;

        double sum = 0.0;
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (rtt[i] <= median) {
                sum += offset[i];
                m++;
            }
        }
        double scatter = 0.0;
        if (m >= 2) {
            double mean = sum / m;
            double sq = 0.0;
            for (int i = 0; i < n; i++) {
                if (rtt[i] <= median) sq += (offset[i] - mean) * (offset[i] - mean);
            }
            scatter = Math.sqrt(sq / m);
        }
        double precision = Math.max(scatter, Math.max(0.25 * jitter, 0.05 * min));
        return new Stats(n, min, median, rtt[n - 1], jitter, scatter, precision);
    }

    /**
     * Nível de qualidade. [audioPenalty]: 0 = nada, 1 = timestamp de áudio em estimativa reduzida
     * (no máximo "aceitável"), 2 = timestamp instável (no máximo "degradada").
     */
    public static Level level(boolean synced, double lastGoodAgeMs, Stats s, int audioPenalty) {
        if (!synced || s == null || lastGoodAgeMs > 10_000.0 || s.rttMin > 500.0 || s.precision > 20.0) {
            return Level.UNRELIABLE;
        }
        Level base;
        if (s.jitter <= 1.0 && s.rttMedian <= 20.0 && s.precision <= 1.0) {
            base = Level.EXCELLENT;
        } else if (s.jitter <= 3.0 && s.rttMedian <= 60.0 && s.precision <= 3.0) {
            base = Level.ACCEPTABLE;
        } else {
            base = Level.DEGRADED;
        }
        if (audioPenalty >= 2 && base.ordinal() < Level.DEGRADED.ordinal()) return Level.DEGRADED;
        if (audioPenalty == 1 && base.ordinal() < Level.ACCEPTABLE.ordinal()) return Level.ACCEPTABLE;
        return base;
    }
}
