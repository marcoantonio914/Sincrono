package com.metronomo.diag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Grupos ("ilhas") de aparelhos: cada grupo toca o seu próprio arquivo, todos sincronizados
 * pelo mesmo relógio. O grupo padrão ("g1", "Geral") existe sempre e não pode ser removido;
 * aparelho sem grupo definido pertence a ele. Seguro para várias threads.
 */
public final class Groups {
    public static final String DEFAULT_ID = "g1";
    public static final int MAX_GROUPS = 16;
    public static final int MAX_NAME = 40;

    private final Map<String, String> names = new LinkedHashMap<>();
    private final Map<String, String> assign = new TreeMap<>();
    private long revision = 1;
    private int next = 2;

    public Groups() {
        names.put(DEFAULT_ID, "Geral");
    }

    static String cleanName(String n) {
        if (n == null) return "";
        String t = n.trim().replaceAll("[\\p{Cntrl}]", "");
        return t.length() > MAX_NAME ? t.substring(0, MAX_NAME) : t;
    }

    /** Cria um grupo e devolve o id, ou null se o nome é vazio ou o limite foi atingido. */
    public synchronized String add(String name) {
        String n = cleanName(name);
        if (n.isEmpty() || names.size() >= MAX_GROUPS) return null;
        String id = "g" + (next++);
        names.put(id, n);
        revision++;
        return id;
    }

    public synchronized boolean rename(String id, String name) {
        String n = cleanName(name);
        if (n.isEmpty() || !names.containsKey(id)) return false;
        if (n.equals(names.get(id))) return true;
        names.put(id, n);
        revision++;
        return true;
    }

    /** Remove um grupo (menos o padrão); seus aparelhos voltam ao padrão. */
    public synchronized boolean remove(String id) {
        if (DEFAULT_ID.equals(id) || !names.containsKey(id)) return false;
        names.remove(id);
        assign.values().removeIf(g -> g.equals(id));
        revision++;
        return true;
    }

    public synchronized boolean assign(String device, String group) {
        if (device == null || device.isEmpty() || device.length() > 80 || !names.containsKey(group)) return false;
        String old = assign.get(device);
        if (DEFAULT_ID.equals(group)) {
            if (old != null) { assign.remove(device); revision++; }
        } else if (!group.equals(old)) {
            assign.put(device, group);
            revision++;
        }
        return true;
    }

    public synchronized String groupOf(String device) {
        String g = assign.get(device);
        return g == null ? DEFAULT_ID : g;
    }

    public synchronized boolean exists(String id) {
        return names.containsKey(id);
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized List<String> ids() {
        return new ArrayList<>(names.keySet());
    }

    public synchronized String name(String id) {
        return names.get(id);
    }

    public synchronized Map<String, String> assignments() {
        return new TreeMap<>(assign);
    }

    /** Restaura o estado guardado (grupos e atribuições), sem mudar nada se algo for inválido. */
    public synchronized void restore(Map<String, String> savedNames, Map<String, String> savedAssign) {
        for (Map.Entry<String, String> e : savedNames.entrySet()) {
            String id = e.getKey();
            if (!id.matches("g\\d{1,4}") || DEFAULT_ID.equals(id)) continue;
            String n = cleanName(e.getValue());
            if (n.isEmpty() || names.size() >= MAX_GROUPS) continue;
            names.put(id, n);
            next = Math.max(next, Integer.parseInt(id.substring(1)) + 1);
        }
        for (Map.Entry<String, String> e : savedAssign.entrySet()) {
            if (names.containsKey(e.getValue()) && !DEFAULT_ID.equals(e.getValue())) assign.put(e.getKey(), e.getValue());
        }
        revision++;
    }
}
