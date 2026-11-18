import com.metronomo.calibration.CalibrationDsp;
import com.metronomo.calibration.SelfCalibrationDsp;
import java.util.Random;

/** Simula um aparelho tocando e gravando o próprio som; confere a autocalibração. */
public class SelfCalTest {
    static int fails = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "OK    " : "FALHA ") + name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        if (!ok) fails++;
    }

    /** trueDelayMs: quanto o som sai depois do planejado. missing: índices não tocados. */
    static short[] record(int sr, double originMs, double[] times, double trueDelayMs, double pathMs,
                          double gain, double noise, int[] missing, long seed) {
        int length = (int) ((times[times.length - 1] - originMs + 1200) * sr / 1000.0);
        double[] sig = new double[length];
        Random rnd = new Random(seed);
        for (int j = 0; j < times.length; j++) {
            boolean skip = false;
            for (int m : missing) if (m == j) skip = true;
            if (skip) continue;
            double tStart = (times[j] + trueDelayMs + pathMs - originMs) / 1000.0;
            int i0 = (int) Math.floor(tStart * sr);
            for (int i = i0; i < i0 + (int) (0.045 * sr) && i < length; i++) {
                if (i < 0) continue;
                sig[i] += gain * CalibrationDsp.chirpSample(i / (double) sr - tStart, 1.0);
            }
        }
        short[] out = new short[length];
        for (int i = 0; i < length; i++) {
            double v = sig[i] + noise * rnd.nextGaussian();
            out[i] = (short) Math.round(Math.max(-1, Math.min(1, v)) * 32767);
        }
        return out;
    }

    static double[] schedule(double t0, int n, double step) {
        double[] t = new double[n];
        for (int i = 0; i < n; i++) t[i] = t0 + i * step;
        return t;
    }

    public static void main(String[] a) {
        for (int sr : new int[] {48000, 44100}) {
            double origin = 100_000.0 + 2.37;
            double[] times = schedule(origin + 2400, 8, 500);

            // atraso verdadeiro positivo, negativo e pequeno
            for (double delay : new double[] {23.4, -7.25, 0.6, 61.0}) {
                short[] rec = record(sr, origin, times, delay, 0.0, 0.5, 0.01, new int[0], 11);
                SelfCalibrationDsp.Result r = SelfCalibrationDsp.analyze(rec, rec.length, sr, origin, 0.3, times);
                check(sr + " Hz, atraso " + delay + " ms medido", r.ok && Math.abs(r.offsetMs - delay) < 0.1,
                        r.ok ? String.format("%.3f ms, variação %.3f, %s", r.offsetMs, r.spreadMs, r.quality) : r.error);
            }

            // origem do carimbo errada em 30 ms: a janela de busca ainda acha, e o erro aparece inteiro no resultado
            {
                short[] rec = record(sr, origin, times, 10.0, 0.0, 0.5, 0.01, new int[0], 12);
                SelfCalibrationDsp.Result r = SelfCalibrationDsp.analyze(rec, rec.length, sr, origin + 30.0, 0.3, times);
                check(sr + " Hz, origem declarada 30 ms adiantada soma 30 ms ao resultado (esperado)", r.ok && Math.abs(r.offsetMs - (10.0 + 30.0)) < 0.1,
                        r.ok ? String.format("%.3f ms", r.offsetMs) : r.error);
            }

            // caminho acústico de 5 cm (~0,15 ms) soma ao resultado
            {
                short[] rec = record(sr, origin, times, 12.0, 0.146, 0.5, 0.01, new int[0], 13);
                SelfCalibrationDsp.Result r = SelfCalibrationDsp.analyze(rec, rec.length, sr, origin, 0.0, times);
                check(sr + " Hz, 5 cm de distância soma ~0,15 ms", r.ok && Math.abs(r.offsetMs - 12.146) < 0.1,
                        r.ok ? String.format("%.3f ms", r.offsetMs) : r.error);
            }

            // dois sons perdidos (aparelho ainda aquecendo): ainda aceita
            {
                short[] rec = record(sr, origin, times, 18.0, 0.0, 0.5, 0.01, new int[] {0, 1}, 14);
                SelfCalibrationDsp.Result r = SelfCalibrationDsp.analyze(rec, rec.length, sr, origin, 0.0, times);
                check(sr + " Hz, 2 sons perdidos ainda mede", r.ok && r.count == 6 && Math.abs(r.offsetMs - 18.0) < 0.1,
                        r.ok ? "count=" + r.count : r.error);
            }

            // quatro sons perdidos: poucos para aceitar
            {
                short[] rec = record(sr, origin, times, 18.0, 0.0, 0.5, 0.01, new int[] {0, 1, 2, 3}, 15);
                SelfCalibrationDsp.Result r = SelfCalibrationDsp.analyze(rec, rec.length, sr, origin, 0.0, times);
                check(sr + " Hz, 4 sons perdidos é recusado", !r.ok, r.ok ? "aceitou" : r.error);
            }

            // silêncio / ruído
            {
                short[] rec = record(sr, origin, times, 18.0, 0.0, 0.0, 0.02, new int[0], 16);
                SelfCalibrationDsp.Result r = SelfCalibrationDsp.analyze(rec, rec.length, sr, origin, 0.0, times);
                check(sr + " Hz, só ruído é recusado", !r.ok, r.ok ? "aceitou" : r.error);
            }

            // saturado
            {
                short[] rec = record(sr, origin, times, 18.0, 0.0, 3.0, 0.01, new int[0], 17);
                SelfCalibrationDsp.Result r = SelfCalibrationDsp.analyze(rec, rec.length, sr, origin, 0.0, times);
                check(sr + " Hz, sinal estourado é recusado", !r.ok && r.error.contains("estourou"), r.ok ? "aceitou" : r.error);
            }

            // confiança: carimbo instável derruba
            {
                short[] rec = record(sr, origin, times, 18.0, 0.0, 0.5, 0.01, new int[0], 18);
                SelfCalibrationDsp.Result lowOrigin = SelfCalibrationDsp.analyze(rec, rec.length, sr, origin, 7.0, times);
                SelfCalibrationDsp.Result midOrigin = SelfCalibrationDsp.analyze(rec, rec.length, sr, origin, 3.0, times);
                SelfCalibrationDsp.Result good = SelfCalibrationDsp.analyze(rec, rec.length, sr, origin, 0.4, times);
                check(sr + " Hz, carimbo instável 7 ms = low", lowOrigin.quality.equals("low"), lowOrigin.quality);
                check(sr + " Hz, carimbo 3 ms = no máximo medium (variação do carimbo conta)", !midOrigin.quality.equals("high"), midOrigin.quality);
                check(sr + " Hz, medida limpa = high", good.quality.equals("high"), good.quality + " " + good.spreadMs);
            }

            // jitter de saída em cada som (±1,5 ms): mediana continua perto, confiança cai
            {
                Random rnd = new Random(5);
                double[] t2 = times.clone();
                double[] jit = new double[t2.length];
                short[] acc = null;
                int length = (int) ((t2[t2.length - 1] - origin + 1200) * sr / 1000.0);
                double[] sig = new double[length];
                for (int j = 0; j < t2.length; j++) {
                    jit[j] = (rnd.nextDouble() * 2 - 1) * 1.5;
                    double tStart = (t2[j] + 15.0 + jit[j] - origin) / 1000.0;
                    int i0 = (int) Math.floor(tStart * sr);
                    for (int i = i0; i < i0 + (int) (0.045 * sr) && i < length; i++) {
                        sig[i] += 0.5 * CalibrationDsp.chirpSample(i / (double) sr - tStart, 1.0);
                    }
                }
                short[] rec = new short[length];
                for (int i = 0; i < length; i++) rec[i] = (short) Math.round(Math.max(-1, Math.min(1, sig[i] + 0.01 * rnd.nextGaussian())) * 32767);
                SelfCalibrationDsp.Result r = SelfCalibrationDsp.analyze(rec, rec.length, sr, origin, 0.2, t2);
                check(sr + " Hz, jitter ±1,5 ms: mediana perto de 15", r.ok && Math.abs(r.offsetMs - 15.0) < 1.5,
                        r.ok ? String.format("%.2f ms, variação %.2f, %s", r.offsetMs, r.spreadMs, r.quality) : r.error);
            }
        }
        System.out.println(fails == 0 ? "\nTodos os testes passaram." : "\n" + fails + " teste(s) falharam.");
        System.exit(fails == 0 ? 0 : 1);
    }
}
