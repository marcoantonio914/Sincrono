package com.metronomo.calibration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Autocalibração: o aparelho toca o som de teste e o PRÓPRIO microfone grava (alto-falante perto do
 * microfone, ou fone encostado nele, ou cabo de loopback). Java puro, sem dependência do Android.
 *
 * Diferente da calibração em conjunto (que mede diferenças entre aparelhos e precisa de uma
 * referência), aqui a medida é ABSOLUTA: para cada som, o horário em que ele foi gravado menos o
 * horário em que o app combinou que ele soaria. O resultado é "este aparelho soa X ms depois do
 * planejado", isto é, o ajuste (positivo = tocar mais cedo) que faz o som sair no horário do relógio
 * compartilhado.
 *
 * O horário de cada amostra gravada vem do carimbo de tempo do AudioRecord (o instante em que o
 * quadro foi capturado, já contando o atraso de entrada do microfone). Sem esse carimbo não há como
 * separar o atraso de saída do de entrada, e a medida não deve ser aplicada.
 */
public final class SelfCalibrationDsp {
    private SelfCalibrationDsp() {}

    /** Menor número de sons ouvidos para aceitar o resultado. */
    public static final int MIN_COUNT = 5;
    /** Se o carimbo de gravação varia mais que isso (ms), a confiança cai. */
    public static final double ORIGIN_SPREAD_MEDIUM_MS = 2.0;
    public static final double ORIGIN_SPREAD_LOW_MS = 5.0;
    /** Metade da janela de busca ao redor de cada horário previsto (s). */
    private static final double SEARCH_HALF_S = 0.2;

    public static final class Result {
        public final boolean ok;
        public final String error;
        /** Quanto o aparelho soa DEPOIS do planejado, em ms (= ajuste "tocar mais cedo"). */
        public final double offsetMs;
        /** Variação entre as medições (já sem os extremos quando há muitas), em ms. */
        public final double spreadMs;
        /** Sons aproveitados e sons tocados. */
        public final int count;
        public final int total;
        /** "high", "medium" ou "low". */
        public final String quality;
        /** Erro de cada som, em ms (NaN = não ouvido). */
        public final double[] errorsMs;

        Result(boolean ok, String error, double offsetMs, double spreadMs, int count, int total, String quality, double[] errorsMs) {
            this.ok = ok;
            this.error = error;
            this.offsetMs = offsetMs;
            this.spreadMs = spreadMs;
            this.count = count;
            this.total = total;
            this.quality = quality;
            this.errorsMs = errorsMs;
        }

        static Result failure(String error, int total, double[] errorsMs) {
            return new Result(false, error, Double.NaN, Double.NaN, 0, total, "low", errorsMs);
        }
    }

    /**
     * @param rec           gravação mono, 16 bits
     * @param length        amostras válidas
     * @param sampleRate    taxa da gravação
     * @param originMs      instante (relógio do servidor, ms) em que a amostra 0 foi capturada
     * @param originSpreadMs variação das estimativas desse instante (ms); 0 se só houver uma
     * @param playTimesMs   horários (servidor, ms) em que cada som deveria soar
     */
    public static Result analyze(short[] rec, int length, int sampleRate, double originMs, double originSpreadMs,
                                 double[] playTimesMs) {
        int total = playTimesMs.length;
        double[] errors = new double[total];
        Arrays.fill(errors, Double.NaN);
        if (total < MIN_COUNT || length < sampleRate / 2 || Double.isNaN(originMs)) {
            return Result.failure("Parâmetros inválidos.", total, errors);
        }

        float[] x = new float[length];
        int peak = 0;
        for (int i = 0; i < length; i++) {
            x[i] = rec[i] / 32768f;
            int a = Math.abs((int) rec[i]);
            if (a > peak) peak = a;
        }
        if (peak >= 32_000) {
            return Result.failure("O som gravado estourou (saturou). Diminua o volume do aparelho e tente de novo.", total, errors);
        }

        float[][] ref = CalibrationDsp.reference(sampleRate);
        int half = (int) (SEARCH_HALF_S * sampleRate);
        List<Double> found = new ArrayList<>();
        for (int j = 0; j < total; j++) {
            double expected = (playTimesMs[j] - originMs) * sampleRate / 1000.0;
            double arrival = CalibrationDsp.findArrival(x, length, ref[0], ref[1], expected, half);
            if (Double.isNaN(arrival)) continue;
            double heardMs = originMs + arrival * 1000.0 / sampleRate;
            errors[j] = heardMs - playTimesMs[j];
            found.add(errors[j]);
        }

        if (found.size() < MIN_COUNT) {
            return Result.failure("Ouvi poucos sons do próprio aparelho (" + found.size() + " de " + total + "). "
                    + "Aumente o volume, fique em silêncio, encoste o aparelho (ou o fone) no microfone e confira a permissão.",
                    total, errors);
        }

        double[] sorted = new double[found.size()];
        for (int i = 0; i < sorted.length; i++) sorted[i] = found.get(i);
        Arrays.sort(sorted);
        int m = sorted.length / 2;
        double median = sorted.length % 2 == 1 ? sorted[m] : 0.5 * (sorted[m - 1] + sorted[m]);
        // Com 6 ou mais medidas, o maior e o menor são descartados para a variação (uma medida ruim não condena o resto).
        int lo = sorted.length >= 6 ? 1 : 0;
        int hi = sorted.length >= 6 ? sorted.length - 2 : sorted.length - 1;
        double spread = sorted[hi] - sorted[lo];
        double worst = Math.max(spread, originSpreadMs);

        if (Math.abs(median) > CalibrationDsp.MAX_ABS_OFFSET_MS) {
            return Result.failure(String.format(java.util.Locale.ROOT,
                    "A medida deu %+.0f ms, fora do esperado. Refaça em silêncio; se repetir, este aparelho não é adequado à autocalibração.",
                    median), total, errors);
        }

        String quality = CalibrationDsp.confidence(worst, found.size());
        if (originSpreadMs > ORIGIN_SPREAD_LOW_MS) quality = "low";
        else if (originSpreadMs > ORIGIN_SPREAD_MEDIUM_MS && quality.equals("high")) quality = "medium";
        return new Result(true, "", median, worst, found.size(), total, quality, errors);
    }
}
