import com.metronomo.calibration.CalibrationDsp;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Simula N aparelhos tocando e gravando ao mesmo tempo; confere analyze() + solve(). */
public class DspTest {
    static final int SR = 48000;
    static final int REPEATS = 4;
    static final int SLOT_SAMPLES = 28800; // 600 ms

    /**
     * delaysMs[j]: atraso do aparelho j (saída + erro de relógio).
     * dist[k][j]: distância (m) do alto-falante de j ao microfone de k.
     * gain[k][j]: quanto o microfone de k ouve o aparelho j (0 = não ouve).
     */
    static short[] record(int k, double[] delaysMs, double[][] dist, double[][] gain, double noise, double driftPpm, long seed) {
        int devices = delaysMs.length;
        int rounds = REPEATS + 1;
        int slots = rounds * devices;
        double slotSec = SLOT_SAMPLES / (double) SR;
        double leadSec = 1.5;
        int length = (int) ((leadSec + slots * slotSec + 1.2) * SR);
        double[] sig = new double[length];
        Random rnd = new Random(seed);
        for (int j = 0; j < slots; j++) {
            int dev = j % devices;
            double g = gain[k][dev];
            if (g == 0) continue;
            double tNom = leadSec + j * slotSec + delaysMs[dev] / 1000.0 + dist[k][dev] / 343.0;
            double t0 = tNom * (1.0 + driftPpm * 1e-6);
            int a = (int) Math.floor(t0 * SR);
            for (int i = a; i < a + 2200 && i < length; i++) {
                if (i < 0) continue;
                sig[i] += CalibrationDsp.chirpSample(i / (double) SR - t0, g)
                        + 0.4 * CalibrationDsp.chirpSample(i / (double) SR - t0 - 0.0035, g);
            }
        }
        short[] rec = new short[length];
        for (int i = 0; i < length; i++) {
            double v = sig[i] + noise * rnd.nextGaussian();
            rec[i] = (short) Math.round(Math.max(-1, Math.min(1, v)) * 32767);
        }
        return rec;
    }

    static void scenario(String name, double[] delaysMs, double[][] dist, double[][] gain, boolean[] isRecorder, double noise) {
        scenario(name, delaysMs, dist, gain, isRecorder, noise, false);
    }

    static void scenario(String name, double[] delaysMs, double[][] dist, double[][] gain, boolean[] isRecorder, double noise, boolean cable) {
        int n = delaysMs.length;
        List<CalibrationDsp.Measurement> ms = new ArrayList<>();
        StringBuilder log = new StringBuilder();
        for (int k = 0; k < n; k++) {
            if (!isRecorder[k]) continue;
            short[] rec = record(k, delaysMs, dist, gain, noise, 30 + 10 * k, 100 + k);
            CalibrationDsp.Analysis a = CalibrationDsp.analyze(rec, rec.length, SR, (1.5 + 0.2) * SR, SLOT_SAMPLES, n, REPEATS, k, cable);
            if (!a.ok) {
                log.append(String.format("    gravador %d: FALHOU (%s)%n", k, a.error.substring(0, Math.min(40, a.error.length()))));
                continue;
            }
            StringBuilder heard = new StringBuilder();
            for (int j = 0; j < n; j++) {
                if (j == k) continue;
                if (Double.isNaN(a.offsetsMs[j])) { heard.append(j).append("=nao ouvido "); continue; }
                heard.append(j).append("=ok ");
                ms.add(new CalibrationDsp.Measurement(k, j, a.offsetsMs[j], a.spreadMs[j], a.counts[j]));
            }
            log.append(String.format("    gravador %d ouviu: %s%n", k, heard));
        }
        CalibrationDsp.Solution sol = CalibrationDsp.solve(n, ms);
        System.out.printf("%n[%s]%n%s", name, log);
        double worst = 0;
        for (int j = 0; j < n; j++) {
            double truth = delaysMs[j] - delaysMs[0];
            double err = sol.offsetsMs[j] - truth;
            if (!Double.isNaN(err)) worst = Math.max(worst, Math.abs(err));
            System.out.printf("    aparelho %d: medido=%8.3f  verdade=%8.3f  erro=%7.3f  variacao=%.2f  ligacoes=%d%n",
                    j, sol.offsetsMs[j], truth, err, sol.spreadMs[j], sol.links[j]);
        }
        System.out.printf("    pior erro: %.3f ms%n", worst);
    }

    public static void main(String[] args) {
        double[] delays = {30.0, 47.3, 21.9, 58.2, 36.6};
        int n = delays.length;

        // 1) todos ouvem todos bem, distancias zero (limite teorico)
        double[][] d0 = new double[n][n], g1 = new double[n][n];
        for (int k = 0; k < n; k++) for (int j = 0; j < n; j++) g1[k][j] = k == j ? 0.9 : 0.5;
        boolean[] all = {true, true, true, true, true};
        scenario("todos gravam, geometria ideal", delays, d0, g1, all, 0.05);

        // 2) geometria real: 5 celulares em fila (10 a 40 cm), ruido
        double[][] dist = new double[n][n];
        for (int k = 0; k < n; k++) for (int j = 0; j < n; j++) dist[k][j] = k == j ? 0.03 : 0.1 * Math.abs(k - j) + 0.03;
        scenario("todos gravam, celulares em fila, ruido", delays, dist, g1, all, 0.12);

        // 3) o gravador 0 (anfitriao) NAO ouve o aparelho 3 nem o 4; os outros ouvem
        double[][] g3 = new double[n][n];
        for (int k = 0; k < n; k++) for (int j = 0; j < n; j++) g3[k][j] = k == j ? 0.9 : 0.5;
        g3[0][3] = 0; g3[0][4] = 0;
        scenario("anfitriao nao ouve 3 e 4, mas os clientes ouvem", delays, dist, g3, all, 0.08);

        // 4) so o anfitriao grava (comportamento antigo) nesse mesmo cenario
        scenario("so o anfitriao grava (antigo): nao ouve 3 e 4", delays, dist, g3, new boolean[]{true, false, false, false, false}, 0.08);

        // 5) dois aparelhos sem microfone permitido (so tocam), 3 gravam
        scenario("so 0, 2 e 4 gravam; 1 e 3 so tocam", delays, dist, g1, new boolean[]{true, false, true, false, true}, 0.08);

        // 6) aparelho completamente mudo (ninguem ouve o 2)
        double[][] g6 = new double[n][n];
        for (int k = 0; k < n; k++) for (int j = 0; j < n; j++) g6[k][j] = (k == j ? 0.9 : 0.5);
        for (int k = 0; k < n; k++) if (k != 2) g6[k][2] = 0;
        scenario("aparelho 2 que ninguem ouve", delays, dist, g6, all, 0.08);

        // 7) modo cabo: todos ligados a uma mesa que soma tudo; so o aparelho 2 grava; sinal limpo, sem distancia
        double[][] gCable = new double[n][n];
        for (int k = 0; k < n; k++) for (int j = 0; j < n; j++) gCable[k][j] = 0.4;
        scenario("cabo: so o aparelho 2 grava (soma da mesa), sinal limpo", delays, d0, gCable, new boolean[]{false, false, true, false, false}, 0.002, true);
    }
}
