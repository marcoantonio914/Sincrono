package com.metronomo.diag;

/**
 * Ganho que persegue um alvo com variação limitada por quadro, para que mudar o volume
 * (do próprio aparelho ou pelo anfitrião) nunca produza estalos. Uma instância por trilha;
 * usada só pela thread de áudio.
 */
public final class GainRamp {
    private double current;
    private final double maxStep;

    /** [rampMs] é o tempo para ir de 0 a 1 (ou de 1 a 0). */
    public GainRamp(double initial, double rampMs, double sampleRate) {
        this.current = initial;
        this.maxStep = 1.0 / Math.max(1.0, rampMs * sampleRate / 1000.0);
    }

    public double current() {
        return current;
    }

    /** Devolve o ganho do próximo quadro, aproximando-se de [target]. */
    public double next(double target) {
        double d = target - current;
        if (d > maxStep) d = maxStep;
        else if (d < -maxStep) d = -maxStep;
        current += d;
        return current;
    }
}
