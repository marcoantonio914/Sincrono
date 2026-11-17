import com.metronomo.calibration.CalibrationDsp;
import com.metronomo.diag.AudioClockTracker;
import com.metronomo.diag.SyncMath;
import java.util.Random;

/** Testes puros (JVM) do diagnóstico: estatísticas de relógio, rastreador de timestamp, confiança da calibração. */
public class SyncTest {
    static int failures = 0;

    static void check(String name, boolean ok, String extra) {
        if (!ok) failures++;
        System.out.printf("%s %s %s%n", ok ? "ok  " : "FALHA", name, extra);
    }

    /** Simula amostras NTP (como ClockSync) e compara a precisão estimada com o erro real do offset escolhido. */
    static void clockScenario(String name, double jitterMs, double baseRtt, double burstChance, double asymmetryMs, double expectErrMaxMs, SyncMath.Level expectAtLeast) {
        Random rnd = new Random(11);
        double trueOffset = 1234.5;
        double[] rtt = new double[12];
        double[] off = new double[12];
        int n = 0;
        double worstActual = 0, worstEstimated = 0;
        SyncMath.Level lastLevel = SyncMath.Level.UNRELIABLE;
        for (int i = 0; i < 400; i++) {
            double out = baseRtt / 2 + Math.abs(rnd.nextGaussian()) * jitterMs + (rnd.nextDouble() < burstChance ? 30 * rnd.nextDouble() : 0);
            double back = baseRtt / 2 + Math.abs(rnd.nextGaussian()) * jitterMs;
            double sampleRtt = out + back;
            // offset medido: erro = (out - back)/2 + efeito da assimetria constante
            double sampleOffset = trueOffset + (out - back) / 2 + asymmetryMs / 2;
            if (n == 12) {
                System.arraycopy(rtt, 1, rtt, 0, 11);
                System.arraycopy(off, 1, off, 0, 11);
                n = 11;
            }
            rtt[n] = sampleRtt;
            off[n] = sampleOffset;
            n++;
            if (i < 30) continue;
            SyncMath.Stats s = SyncMath.stats(rtt, off, n);
            int best = 0;
            for (int k = 1; k < n; k++) if (rtt[k] < rtt[best]) best = k;
            double actual = Math.abs(off[best] - trueOffset - asymmetryMs / 2); // erro além da assimetria constante
            worstActual = Math.max(worstActual, actual);
            worstEstimated = Math.max(worstEstimated, s.precision);
            lastLevel = SyncMath.level(true, 500, s, 0);
        }
        boolean levelOk = lastLevel.ordinal() <= expectAtLeast.ordinal();
        check(name, worstActual <= expectErrMaxMs && levelOk,
                String.format("erro real máx=%.2f ms, precisão estimada máx=%.2f ms, nível final=%s", worstActual, worstEstimated, lastLevel));
    }

    public static void main(String[] args) {
        System.out.println("== relógio ==");
        clockScenario("rede boa (5 GHz, perto)", 0.3, 6, 0.0, 0, 1.0, SyncMath.Level.EXCELLENT);
        clockScenario("rede doméstica razoável", 1.5, 12, 0.02, 0, 3.0, SyncMath.Level.ACCEPTABLE);
        clockScenario("Wi-Fi congestionado, rajadas de atraso", 6.0, 25, 0.15, 0, 8.0, SyncMath.Level.DEGRADED);

        SyncMath.Stats good = SyncMath.stats(new double[]{5, 5, 5, 5}, new double[]{10, 10, 10, 10}, 4);
        check("não sincronizado = não confiável", SyncMath.level(false, 0, good, 0) == SyncMath.Level.UNRELIABLE, "");
        check("amostra antiga (>10 s) = não confiável", SyncMath.level(true, 12_000, good, 0) == SyncMath.Level.UNRELIABLE, "");
        check("timestamp de áudio instável rebaixa para degradada", SyncMath.level(true, 100, good, 2) == SyncMath.Level.DEGRADED, "");
        check("fallback de áudio limita a aceitável", SyncMath.level(true, 100, good, 1) == SyncMath.Level.ACCEPTABLE, "");
        check("sem amostras", SyncMath.stats(new double[0], new double[0], 0) == null, "");

        System.out.println("== timestamp do AudioTrack ==");
        int sr = 48000;
        // a) timestamps perfeitos, com deriva do relógio de áudio de 200 ppm
        {
            AudioClockTracker t = new AudioClockTracker(sr, 20e6, 0);
            double drift = 1 + 200e-6;
            for (int i = 0; i < 400; i++) {
                double nowNs = i * 10e6;
                long frame = (long) (nowNs / 1e9 * sr / drift);
                t.update(nowNs, true, frame, (long) nowNs, 0);
            }
            check("timestamps estáveis = PRECISE", t.state(4e9) == AudioClockTracker.State.PRECISE && t.totalJumps() == 0, "saltos=" + t.totalJumps());
        }
        // b) sem timestamp: aquecendo, depois FALLBACK
        {
            AudioClockTracker t = new AudioClockTracker(sr, 20e6, 0);
            t.update(0.5e9, false, 0, 0, 0);
            boolean warming = t.state(0.5e9) == AudioClockTracker.State.WARMING_UP;
            for (int i = 0; i < 100; i++) {
                double nowNs = 1e9 + i * 20e6;
                t.update(nowNs, false, 0, 0, (long) ((nowNs - 1e9) / 1e9 * sr));
            }
            check("sem timestamp: aquece e vira FALLBACK", warming && t.state(3e9) == AudioClockTracker.State.FALLBACK, "estado=" + t.state(3e9));
        }
        // c) saltos repetidos (> 5 ms) -> UNSTABLE
        {
            AudioClockTracker t = new AudioClockTracker(sr, 20e6, 0);
            for (int i = 0; i < 300; i++) {
                double nowNs = i * 10e6;
                long frame = (long) (nowNs / 1e9 * sr);
                long ts = (long) nowNs + ((i % 100) >= 50 ? 8_000_000L : 0L); // alterna +8 ms a cada 0,5 s
                t.update(nowNs, true, frame, ts, 0);
            }
            check("saltos repetidos = UNSTABLE", t.state(3e9) == AudioClockTracker.State.UNSTABLE && t.totalJumps() >= 3, "saltos=" + t.totalJumps());
        }
        // d) timestamp que volta no tempo é ignorado e rebaixa
        {
            AudioClockTracker t = new AudioClockTracker(sr, 20e6, 0);
            for (int i = 0; i < 50; i++) t.update(i * 10e6, true, (long) (i * 480), (long) (i * 10e6), 0);
            double before = t.interceptNs();
            t.update(500e6, true, 100, 1_000_000L, 0);
            check("fora de ordem é ignorado e rebaixa", t.outOfOrderCount() == 1 && t.interceptNs() == before && t.state(500e6) == AudioClockTracker.State.UNSTABLE, "");
        }
        // e) timestamps que param de chegar
        {
            AudioClockTracker t = new AudioClockTracker(sr, 20e6, 0);
            for (int i = 0; i < 50; i++) t.update(i * 10e6, true, (long) (i * 480), (long) (i * 10e6), 0);
            check("timestamps param = UNSTABLE", t.state(0.5e9) == AudioClockTracker.State.PRECISE && t.state(3.5e9) == AudioClockTracker.State.UNSTABLE, "");
        }

        System.out.println("== confiança da calibração ==");
        check("variação pequena = high", CalibrationDsp.confidence(0.9, 4).equals("high"), "");
        check("variação média = medium", CalibrationDsp.confidence(3.0, 4).equals("medium"), "");
        check("variação grande = low", CalibrationDsp.confidence(8.0, 4).equals("low"), "");
        check("poucas repetições = low", CalibrationDsp.confidence(0.5, 1).equals("low"), "");
        check("sem medida = low", CalibrationDsp.confidence(Double.NaN, 4).equals("low"), "");

        System.out.println(failures == 0 ? "tudo certo" : "FALHAS: " + failures);
        if (failures != 0) System.exit(1);
    }
}
