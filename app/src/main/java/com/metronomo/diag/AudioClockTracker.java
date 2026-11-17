package com.metronomo.diag;

/**
 * Valida e suaviza o mapeamento "quadro de áudio -> instante em que será ouvido" a partir de
 * AudioTrack.getTimestamp() (Java puro, testável na JVM; o lado Android só repassa os números).
 *
 * "Intercept" = instante (ns, relógio monotônico) em que o quadro 0 do fluxo seria ouvido. O
 * instante do quadro n é intercept + n * 1e9 / sampleRate.
 *
 * Estados:
 *  - WARMING_UP: ainda sem estimativa;
 *  - PRECISE: timestamps do sistema válidos, monotônicos e estáveis;
 *  - FALLBACK: o aparelho não informa timestamp; estimativa por posição de reprodução (menos precisa);
 *  - UNSTABLE: timestamps com saltos repetidos, fora de ordem ou que pararam de chegar.
 *
 * Nenhum método aloca memória (pode ser chamado da thread de áudio).
 */
public final class AudioClockTracker {

    public enum State { WARMING_UP, PRECISE, FALLBACK, UNSTABLE }

    /** Diferença que reinicia a estimativa (e conta como "salto"). */
    private static final double JUMP_NS = 5e6;
    private static final double SMOOTHING = 0.05;
    private static final double FALLBACK_AFTER_NS = 1.5e9;
    private static final double STALE_NS = 2.0e9;
    private static final double JUMP_WINDOW_NS = 10e9;
    private static final int UNSTABLE_JUMPS = 3;
    private static final int RING = 8;

    private final int sampleRate;
    private final double fallbackLatencyNs;
    private final double startNs;

    private double intercept = Double.NaN;
    private boolean usingTimestamps = false;
    private double lastValidNs = Double.NaN;
    private long lastFrame = Long.MIN_VALUE;
    private long lastTimestampNs = Long.MIN_VALUE;

    private final double[] jumpTimes = new double[RING];
    private int jumpWrite = 0;
    private int totalJumps = 0;
    private int outOfOrder = 0;
    private double lastOutOfOrderNs = Double.NEGATIVE_INFINITY;

    public AudioClockTracker(int sampleRate, double fallbackLatencyNs, double nowNs) {
        this.sampleRate = sampleRate;
        this.fallbackLatencyNs = fallbackLatencyNs;
        this.startNs = nowNs;
        for (int i = 0; i < RING; i++) jumpTimes[i] = Double.NEGATIVE_INFINITY;
    }

    /**
     * Chamado a cada bloco. [timestampValid]: getTimestamp() devolveu true. [headFrames]: posição de
     * reprodução (só usada quando não há timestamp; passe 0 se não leu).
     */
    public void update(double nowNs, boolean timestampValid, long framePosition, long timestampNs, long headFrames) {
        if (timestampValid) {
            if (framePosition < lastFrame || timestampNs < lastTimestampNs) {
                outOfOrder++;
                lastOutOfOrderNs = nowNs;
                return;
            }
            lastFrame = framePosition;
            lastTimestampNs = timestampNs;
            lastValidNs = nowNs;
            usingTimestamps = true;
            observe(nowNs, timestampNs - framePosition * 1e9 / sampleRate);
        } else if (!usingTimestamps && nowNs - startNs > FALLBACK_AFTER_NS) {
            observe(nowNs, nowNs - headFrames * 1e9 / sampleRate + fallbackLatencyNs);
        }
    }

    private void observe(double nowNs, double observed) {
        if (Double.isNaN(intercept)) {
            intercept = observed;
            return;
        }
        if (Math.abs(observed - intercept) > JUMP_NS) {
            intercept = observed;
            jumpTimes[jumpWrite] = nowNs;
            jumpWrite = (jumpWrite + 1) % RING;
            totalJumps++;
        } else {
            intercept += (observed - intercept) * SMOOTHING;
        }
    }

    /** Já chegou algum timestamp válido do sistema (então não é preciso ler a posição de reprodução). */
    public boolean usingTimestamps() {
        return usingTimestamps;
    }

    /** NaN até haver estimativa. */
    public double interceptNs() {
        return intercept;
    }

    public int totalJumps() {
        return totalJumps;
    }

    public int outOfOrderCount() {
        return outOfOrder;
    }

    public State state(double nowNs) {
        if (Double.isNaN(intercept)) return State.WARMING_UP;
        if (!usingTimestamps) return State.FALLBACK;
        int recent = 0;
        for (int i = 0; i < RING; i++) {
            if (nowNs - jumpTimes[i] < JUMP_WINDOW_NS) recent++;
        }
        if (recent >= UNSTABLE_JUMPS) return State.UNSTABLE;
        if (nowNs - lastOutOfOrderNs < JUMP_WINDOW_NS) return State.UNSTABLE;
        if (nowNs - lastValidNs > STALE_NS) return State.UNSTABLE;
        return State.PRECISE;
    }
}
