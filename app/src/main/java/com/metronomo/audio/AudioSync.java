package com.metronomo.audio;

/**
 * Correção contínua da posição de leitura de um arquivo de áudio.
 *
 * A cada bloco, quem toca calcula em que quadro do arquivo o bloco DEVERIA começar
 * (a partir do relógio do servidor e do horário em que o bloco será ouvido) e informa
 * aqui. Se a posição de leitura real se afastou pouco (deriva do relógio do aparelho,
 * ruído na estimativa), ela é corrigida aos poucos, lendo o arquivo um pouco mais
 * rápido ou mais devagar (no máximo 0,1%, inaudível). Se o erro é grande, salta.
 */
public final class AudioSync {

    /** Acima deste erro (50 ms) a posição salta direto para a correta. */
    public static final double JUMP_SECONDS = 0.050;
    /** Erros menores que isto (0,1 ms) são ignorados. */
    public static final double DEADBAND_SECONDS = 0.0001;
    /** Constante de tempo da correção: o erro é eliminado em ~0,5 s (limitada pela taxa máxima). */
    public static final double TIME_CONSTANT_SECONDS = 0.5;
    public static final double MAX_RATE_DEVIATION = 0.001;

    public static final class Slew {
        /** Quadro do arquivo (fracionário) em que o bloco atual começa a ser lido. */
        public double cursor = Double.NaN;
        /** Quadros do arquivo consumidos por quadro de saída neste bloco (~1,0). */
        public double rate = 1.0;
        /** Diagnóstico: saltos (erro acima de 50 ms), blocos corrigidos e blocos com a velocidade no limite. */
        public int jumps = 0;
        public long chunks = 0;
        public long saturatedChunks = 0;
        /** Erro do último bloco (quadros): posição ideal menos posição de leitura. */
        public double lastError = 0.0;

        public void reset() {
            cursor = Double.NaN;
            rate = 1.0;
        }

        /** Prepara o próximo bloco. [desired] = quadro em que ele deveria começar. */
        public void step(double desired, int sampleRate) {
            chunks++;
            if (Double.isNaN(cursor)) {
                cursor = desired;
                rate = 1.0;
                lastError = 0.0;
                return;
            }
            if (Math.abs(desired - cursor) > JUMP_SECONDS * sampleRate) {
                cursor = desired;
                rate = 1.0;
                jumps++;
                lastError = 0.0;
                return;
            }
            double err = desired - cursor;
            lastError = err;
            if (Math.abs(err) < DEADBAND_SECONDS * sampleRate) {
                rate = 1.0;
            } else {
                double r = err / (TIME_CONSTANT_SECONDS * sampleRate);
                rate = 1.0 + Math.max(-MAX_RATE_DEVIATION, Math.min(MAX_RATE_DEVIATION, r));
                if (Math.abs(r) >= MAX_RATE_DEVIATION) saturatedChunks++;
            }
        }

        /** Avança o cursor depois de gerar [chunkFrames] quadros de saída. */
        public void advance(int chunkFrames) {
            cursor += chunkFrames * rate;
        }
    }

    private AudioSync() {}
}
