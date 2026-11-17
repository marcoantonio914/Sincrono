import com.metronomo.diag.GainRamp;
import com.metronomo.diag.MixLevels;

public class GainTest {
    static int fails = 0;
    static void check(boolean ok, String msg) { System.out.println((ok ? "OK   " : "FALHA ") + msg); if (!ok) fails++; }

    public static void main(String[] a) {
        double sr = 48000;
        GainRamp g = new GainRamp(1.0, 150, sr);
        double prev = g.current(), maxJump = 0;
        int frames = 0;
        while (Math.abs(g.current() - 0.2) > 1e-9 && frames < 100000) { double v = g.next(0.2); maxJump = Math.max(maxJump, Math.abs(v - prev)); prev = v; frames++; }
        double ms = frames * 1000.0 / sr;
        check(Math.abs(ms - 120) < 2, "1.0 -> 0.2 leva ~120 ms (" + ms + ")");
        check(maxJump < 0.0002, "variação máxima por quadro pequena (" + maxJump + ")");
        check(g.next(0.2) == 0.2, "permanece no alvo");
        GainRamp h = new GainRamp(0, 150, sr);
        for (int i = 0; i < 10; i++) h.next(1.0);
        check(h.current() < 0.01, "subida é gradual, não instantânea");

        MixLevels m = new MixLevels();
        check(m.get("x") == 1.0, "padrão 1.0");
        long r0 = m.revision();
        m.set("x", 0.5);
        check(m.get("x") == 0.5 && m.revision() > r0, "define e aumenta revisão");
        long r1 = m.revision();
        m.set("x", 0.5);
        check(m.revision() == r1, "mesmo valor não muda a revisão");
        m.set("y", 7);
        check(m.get("y") == 1.0, "limita a 1");
        m.set("z", -3);
        check(m.get("z") == 0.0, "limita a 0");
        m.set("", 0.2);
        check(m.snapshot().size() == 3, "id vazio ignorado");
        m.set("a\"b\\c", 0.25);
        String j = m.toJson();
        System.out.println(j);
        check(j.contains("\"a\\\"b\\\\c\":0.250"), "escapa aspas e barras");
        System.exit(fails == 0 ? 0 : 1);
    }
}
