import com.metronomo.diag.Groups;
import java.util.*;

public class GroupsTest {
    static int fails = 0;
    static void check(boolean ok, String msg) { System.out.println((ok ? "OK   " : "FALHA ") + msg); if (!ok) fails++; }
    public static void main(String[] a) {
        Groups g = new Groups();
        check(g.groupOf("d1").equals("g1"), "sem grupo = padrão");
        String v = g.add("  Violinos ");
        check(v != null && g.name(v).equals("Violinos"), "cria grupo com nome limpo");
        check(g.add("   ") == null, "nome vazio recusado");
        check(g.assign("d1", v) && g.groupOf("d1").equals(v), "atribui aparelho");
        check(!g.assign("d2", "g99"), "grupo inexistente recusado");
        long r = g.revision();
        g.assign("d1", v);
        check(g.revision() == r, "atribuição repetida não muda revisão");
        check(g.rename(v, "Cordas") && g.name(v).equals("Cordas"), "renomeia");
        check(!g.remove("g1"), "padrão não pode ser removido");
        check(g.remove(v) && g.groupOf("d1").equals("g1"), "remover devolve aparelhos ao padrão");
        for (int i = 0; i < 40; i++) g.add("G" + i);
        check(g.ids().size() == Groups.MAX_GROUPS, "limite de grupos (" + g.ids().size() + ")");
        Groups h = new Groups();
        Map<String,String> n = new HashMap<>(); n.put("g5", "Coral"); n.put("g1", "x"); n.put("zz", "ruim");
        Map<String,String> as = new HashMap<>(); as.put("d1", "g5"); as.put("d2", "g9");
        h.restore(n, as);
        check(h.name("g5").equals("Coral") && h.name("g1").equals("Geral") && h.groupOf("d1").equals("g5") && h.groupOf("d2").equals("g1"), "restaura só o que é válido");
        String novo = h.add("Banda");
        check(novo.equals("g6"), "ids novos não colidem após restaurar (" + novo + ")");
        System.exit(fails == 0 ? 0 : 1);
    }
}
