import com.metronomo.audio.AudioSync;
import java.util.Random;

/** Simula deriva do relógio de áudio e ruído na estimativa do horário de cada bloco. */
public class AudioSyncTest {
    public static void main(String[] args) {
        int sr = 44100;
        int chunk = 512;
        double[] driftsPpm = {0, 50, -120, 300, -300};
        double worst = 0;
        for (double ppm : driftsPpm) {
            Random rnd = new Random(7);
            AudioSync.Slew slew = new AudioSync.Slew();
            // Tempo "verdadeiro" decorrido (s) em que cada bloco é ouvido. O DAC do aparelho
            // consome quadros a (1 + ppm*1e-6) vezes o ritmo do relógio do servidor.
            double dacRatio = 1 + ppm * 1e-6;
            double framesOut = 0;
            double maxErrMs = 0, maxRateDev = 0;
            for (int n = 0; n < 60 * sr / chunk; n++) {
                double trueSeconds = framesOut / sr / dacRatio;
                double noiseSeconds = rnd.nextGaussian() * 0.0003; // 0,3 ms de ruído na estimativa
                double desired = (trueSeconds + noiseSeconds) * sr; // posição ideal em quadros
                slew.step(desired, sr);
                double realErrMs = (trueSeconds * sr - slew.cursor) / sr * 1000;
                if (n > 400) { // depois de assentar (~5 s)
                    maxErrMs = Math.max(maxErrMs, Math.abs(realErrMs));
                    maxRateDev = Math.max(maxRateDev, Math.abs(slew.rate - 1));
                }
                slew.advance(chunk);
                framesOut += chunk;
            }
            System.out.printf("deriva %+5.0f ppm: erro máx (após 5 s) = %.3f ms, desvio máx de velocidade = %.4f%%%n",
                    ppm, maxErrMs, maxRateDev * 100);
            worst = Math.max(worst, maxErrMs);
        }
        // salto grande: deve ressincronizar na hora
        AudioSync.Slew s = new AudioSync.Slew();
        s.step(1000, sr);
        s.advance(chunk);
        s.step(1000 + chunk + 0.5 * sr, sr);
        System.out.println("após salto de 0,5 s: cursor = desired? " + (Math.abs(s.cursor - (1000 + chunk + 0.5 * sr)) < 1e-9));
        System.out.printf("pior erro: %.3f ms%n", worst);
    }
}
