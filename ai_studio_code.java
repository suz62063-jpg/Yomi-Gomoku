import java.util.*;

public class HydroDragonEngine {
    private static final int G = 15;
    private static final int EMPTY = 0;
    private static final int BLACK = 1;
    private static final int WHITE = 2;

    // 评分常量
    private static final int S_WIN = 100000000;
    private static final int S_OF  = 1000000;  // 活四
    private static final int S_SF  = 200000;   // 冲四
    private static final int S_OT  = 20000;    // 活三
    private static final int S_ST  = 2000;     // 眠三
    private static final int S_O2  = 200;      // 活二
    private static final int S_S2  = 20;       // 眠二

    // 置换表常量
    private static final int TT_SZ = 1 << 22;
    private static final byte EXACT = 0;
    private static final byte LOWER = 1;
    private static final byte UPPER = 2;

    // 静态权重表
    private static final int[][] CW = new int[G][G];
    private static final long[][][] ZB = new long[G][G][2];
    private static final Map<String, List<Move>> OPENING_BOOK = new HashMap<>();

    static {
        // 初始化中心偏置权重
        for (int r = 0; r < G; r++) {
            for (int c = 0; c < G; c++) {
                CW[r][c] = Math.max(0, (7 - Math.max(Math.abs(r - 7), Math.abs(c - 7))) * 4);
            }
        }
        // 初始化 Zobrist 随机数
        Random rng = new Random(42);
        for (int r = 0; r < G; r++) {
            for (int c = 0; c < G; c++) {
                ZB[r][c][0] = rng.nextLong();
                ZB[r][c][1] = rng.nextLong();
            }
        }
        // 导入开局定式
        addBookLine(Arrays.asList(m(7,7), m(8,8)), Arrays.asList(m(9,7), m(7,9), m(9,9)));
        addBookLine(Arrays.asList(m(7,7), m(8,8), m(9,7)), Arrays.asList(m(8,7), m(9,6), m(10,7)));
        addBookLine(Arrays.asList(m(7,7), m(8,8), m(7,9)), Arrays.asList(m(7,8), m(6,9), m(8,9)));
        addBookLine(Arrays.asList(m(7,7), m(8,8), m(9,9)), Arrays.asList(m(6,6), m(10,10), m(8,6)));
        addBookLine(Arrays.asList(m(7,7), m(8,7)), Arrays.asList(m(6,7), m(9,7), m(7,8), m(7,6)));
        addBookLine(Arrays.asList(m(7,7), m(8,7), m(6,7)), Arrays.asList(m(7,8), m(8,8), m(5,7)));
        addBookLine(Arrays.asList(m(7,7), m(8,7), m(9,7)), Arrays.asList(m(7,8), m(6,7), m(7,6)));
        addBookLine(Arrays.asList(m(7,7), m(9,9)), Arrays.asList(m(5,5), m(9,7), m(7,9), m(5,9)));
        addBookLine(Arrays.asList(m(7,7), m(9,9), m(5,5)), Arrays.asList(m(8,8), m(7,9), m(9,7)));
        addBookLine(Arrays.asList(m(7,7), m(9,9), m(9,7)), Arrays.asList(m(8,8), m(7,8), m(8,7)));
        addBookLine(Arrays.asList(m(7,7), m(7,8)), Arrays.asList(m(7,6), m(8,7), m(6,7), m(8,8)));
        addBookLine(Arrays.asList(m(7,7), m(7,8), m(7,6)), Arrays.asList(m(8,8), m(6,8), m(8,7)));
        addBookLine(Arrays.asList(m(7,7), m(6,6)), Arrays.asList(m(8,8), m(9,7), m(7,9), m(5,7)));
        addBookLine(Arrays.asList(m(7,7), m(6,6), m(8,8)), Arrays.asList(m(9,9), m(7,9), m(5,7)));
    }

    // 棋盘数据
    private final int[][] board = new int[G][G];
    private final List<Move> history = new ArrayList<>();

    // 置换表
    private final long[] ttKey = new long[TT_SZ];
    private final int[] ttScr = new int[TT_SZ];
    private final byte[] ttDep = new byte[TT_SZ];
    private final byte[] ttFlg = new byte[TT_SZ];

    // 剪枝启发数据
    private static final int MAX_PLY = 40;
    private final int[] killer = new int[MAX_PLY * 2];
    private final int[] hist = new int[G * G];

    // 搜索状态控制
    private long zKey;
    private long nodes;
    private long startT;
    private long timeLimit;
    private boolean aborted;

    // 自适应评估参数
    private double AW = 1.55;
    private double DW = 0.90;

    public static class Move {
        public int r, c, score, ms, os;
        public long sortVal;
        public Move(int r, int c) { this.r = r; this.c = c; }
        public Move(int r, int c, int score) { this.r = r; this.c = c; this.score = score; }
        public Move(int r, int c, int score, int ms, int os) {
            this.r = r; this.c = c; this.score = score; this.ms = ms; this.os = os;
        }
    }

    private static Move m(int r, int c) { return new Move(r, c); }

    private static void addBookLine(List<Move> seq, List<Move> candidates) {
        OPENING_BOOK.put(serializeHistory(seq), candidates);
    }

    private static String serializeHistory(List<Move> histList) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < histList.size(); i++) {
            Move m = histList.get(i);
            sb.append(m.r * 100 + m.c);
            if (i < histList.size() - 1) sb.append(",");
        }
        return sb.toString();
    }

    /**
     * AI决策主入口
     */
    public Move findBestMove(int[][] currentBoard, List<Move> gameHistory, int role, long limitMs,
                             int playerAgg, int playerDef, int gamesPlayed) {
        // 复制棋盘与历史记录
        for (int r = 0; r < G; r++) {
            System.arraycopy(currentBoard[r], 0, board[r], 0, G);
        }
        history.clear();
        history.addAll(gameHistory);

        this.timeLimit = limitMs;
        this.aborted = false;
        this.nodes = 0;
        this.startT = System.currentTimeMillis();

        // 玩家画像分析与参数自适应调节
        if (gamesPlayed < 3) {
            this.AW = 1.55; this.DW = 0.90;
        } else if (playerAgg > playerDef + 15) {
            this.AW = 1.30; this.DW = 1.50; // 对方极具进攻性 -> 我方加强防守防冲四
        } else if (playerDef > playerAgg + 15) {
            this.AW = 2.00; this.DW = 0.70; // 对方防守消极 -> 我方增加进攻权重
        } else {
            this.AW = 1.65; this.DW = 0.95;
        }

        // 基于局部气势(tension)微调防守比重
        double tension = detectTension(board, role);
        if (tension > 0.6) {
            this.DW = Math.min(this.DW + 0.4, 2.0);
        } else if (tension > 0.3) {
            this.DW = Math.min(this.DW + 0.2, 1.8);
        }

        // 计算初始哈希值
        this.zKey = 0;
        for (int r = 0; r < G; r++) {
            for (int c = 0; c < G; c++) {
                int v = board[r][c];
                if (v != EMPTY) {
                    this.zKey ^= ZB[r][c][v == BLACK ? 0 : 1];
                }
            }
        }

        Arrays.fill(killer, 0);

        // 1. 检索定式开局库
        Move bookMove = getOpeningBookMove(role);
        if (bookMove != null) return bookMove;

        // 2. 斩杀与绝对防御检查 (Depth 1)
        for (int r = 0; r < G; r++) {
            for (int c = 0; c < G; c++) {
                if (board[r][c] != EMPTY) continue;
                if (role == BLACK && isForbidden(board, r, c)) continue;
                board[r][c] = role;
                if (checkWin(board, r, c, role)) {
                    board[r][c] = EMPTY;
                    return new Move(r, c);
                }
                board[r][c] = EMPTY;
            }
        }

        int opp = 3 - role;
        for (int r = 0; r < G; r++) {
            for (int c = 0; c < G; c++) {
                if (board[r][c] != EMPTY) continue;
                board[r][c] = opp;
                if (checkWin(board, r, c, opp)) {
                    board[r][c] = EMPTY;
                    return new Move(r, c);
                }
                board[r][c] = EMPTY;
            }
        }

        // 3. VCF (冲四算杀)
        Move vcfMove = findVCF(board, role, 0, 24);
        if (vcfMove != null && !aborted) return vcfMove;
        this.aborted = false;

        // 4. TSS (双三算杀)
        Move tssMove = findTSS(board, role, 0, 16);
        if (tssMove != null && !aborted) return tssMove;
        this.aborted = false;

        // 5. 迭代加深 PVS 主搜索
        int stoneCount = history.size();
        int rootWidth = stoneCount > 40 ? 10 : 16;
        int maxDepth = stoneCount > 40 ? 16 : 14;

        List<Move> rootCands = getCandidates(board, role, rootWidth);
        Move bestMove = rootCands.get(0);
        int finalDepth = 0;

        for (int d = 4; d <= maxDepth; d += 2) {
            if (System.currentTimeMillis() - startT > timeLimit * 0.82) break;
            rootCands.sort((a, b) -> Integer.compare(b.score, a.score));

            Move iterBest = null;
            int iterScore = -S_WIN * 10;

            for (Move m : rootCands) {
                if (role == BLACK && isForbidden(board, m.r, m.c)) continue;
                board[m.r][m.c] = role;
                zKey ^= ZB[m.r][m.c][role == BLACK ? 0 : 1];

                int sc;
                if (checkWin(board, m.r, m.c, role)) {
                    sc = S_WIN;
                } else {
                    sc = -pvs(board, opp, d - 1, -S_WIN * 10, S_WIN * 10, 1);
                }

                board[m.r][m.c] = EMPTY;
                zKey ^= ZB[m.r][m.c][role == BLACK ? 0 : 1];

                if (aborted) break;
                m.score = sc;
                if (sc > iterScore) {
                    iterScore = sc;
                    iterBest = m;
                }
            }

            if (!aborted && iterBest != null) {
                bestMove = iterBest;
                finalDepth = d;
                if (iterScore >= S_WIN - 200) break; // 算出了杀局
            }
            if (aborted) break;
        }

        if (bestMove != null) {
            hist[bestMove.r * G + bestMove.c] += finalDepth * finalDepth + 4;
        }

        return bestMove;
    }

    private double detectTension(int[][] b, int role) {
        int opp = 3 - role;
        int threatCount = 0;
        for (int r = 0; r < G; r++) {
            for (int c = 0; c < G; c++) {
                if (b[r][c] != EMPTY) continue;
                b[r][c] = opp;
                int sc = scoreCell(b, r, c, opp);
                b[r][c] = EMPTY;
                if (sc >= S_OT) threatCount++;
            }
        }
        return Math.min(1.0, threatCount / 3.0);
    }

    public static int scoreCell(int[][] b, int r, int c, int color) {
        int total = 0, OF = 0, SF = 0, OT = 0;

        // 1. 垂直
        int cnt1 = 1, opens1 = 0;
        int nr = r + 1, nc = c;
        while (nr < G && b[nr][nc] == color) { cnt1++; nr++; }
        if (nr < G && b[nr][nc] == EMPTY) opens1 += 1;
        nr = r - 1;
        while (nr >= 0 && b[nr][nc] == color) { cnt1++; nr--; }
        if (nr >= 0 && b[nr][nc] == EMPTY) opens1 += 2;

        // 2. 水平
        int cnt2 = 1, opens2 = 0;
        nr = r; nc = c + 1;
        while (nc < G && b[nr][nc] == color) { cnt2++; nc++; }
        if (nc < G && b[nr][nc] == EMPTY) opens2 += 1;
        nc = c - 1;
        while (nc >= 0 && b[nr][nc] == color) { cnt2++; nc--; }
        if (nc >= 0 && b[nr][nc] == EMPTY) opens2 += 2;

        // 3. 主对角
        int cnt3 = 1, opens3 = 0;
        nr = r + 1; nc = c + 1;
        while (nr < G && nc < G && b[nr][nc] == color) { cnt3++; nr++; nc++; }
        if (nr < G && nc < G && b[nr][nc] == EMPTY) opens3 += 1;
        nr = r - 1; nc = c - 1;
        while (nr >= 0 && nc >= 0 && b[nr][nc] == color) { cnt3++; nr--; nc--; }
        if (nr >= 0 && nc >= 0 && b[nr][nc] == EMPTY) opens3 += 2;

        // 4. 副对角
        int cnt4 = 1, opens4 = 0;
        nr = r + 1; nc = c - 1;
        while (nr < G && nc >= 0 && b[nr][nc] == color) { cnt4++; nr++; nc--; }
        if (nr < G && nc >= 0 && b[nr][nc] == EMPTY) opens4 += 1;
        nr = r - 1; nc = c + 1;
        while (nr >= 0 && nc < G && b[nr][nc] == color) { cnt4++; nr--; nc++; }
        if (nr >= 0 && nc < G && b[nr][nc] == EMPTY) opens4 += 2;

        int[] stats = { (cnt1 << 2) | opens1, (cnt2 << 2) | opens2, (cnt3 << 2) | opens3, (cnt4 << 2) | opens4 };
        for (int i = 0; i < 4; i++) {
            int st = stats[i];
            int cnt = st >> 2;
            int opens = (st & 1) + ((st >> 1) & 1);
            if (cnt >= 5) return S_WIN;
            if (cnt == 4) {
                if (opens == 2) OF++; else if (opens == 1) SF++;
            } else if (cnt == 3) {
                if (opens == 2) OT++; else if (opens == 1) total += S_ST;
            } else if (cnt == 2) {
                total += (opens == 2) ? S_O2 : (opens == 1 ? S_S2 : 0);
            }
        }
        if (OF > 0) return S_OF * (OF >= 2 ? 4 : 1);
        if (SF > 0 && OT > 0) return S_OF * 2;
        if (SF >= 2) return S_SF * 3;
        if (SF > 0) total += S_SF;
        if (OT >= 2) total += S_OT * 4; else if (OT > 0) total += S_OT;
        return total;
    }

    public static boolean checkWin(int[][] b, int r, int c, int color) {
        // 4方向求算
        int cnt = 1, nr = r + 1, nc = c;
        while (nr < G && b[nr][nc] == color) { cnt++; nr++; }
        nr = r - 1;
        while (nr >= 0 && b[nr][nc] == color) { cnt++; nr--; }
        if (color == BLACK ? cnt == 5 : cnt >= 5) return true;

        cnt = 1; nr = r; nc = c + 1;
        while (nc < G && b[nr][nc] == color) { cnt++; nc++; }
        nc = c - 1;
        while (nc >= 0 && b[nr][nc] == color) { cnt++; nc--; }
        if (color == BLACK ? cnt == 5 : cnt >= 5) return true;

        cnt = 1; nr = r + 1; nc = c + 1;
        while (nr < G && nc < G && b[nr][nc] == color) { cnt++; nr++; nc++; }
        nr = r - 1; nc = c - 1;
        while (nr >= 0 && nc >= 0 && b[nr][nc] == color) { cnt++; nr--; nc--; }
        if (color == BLACK ? cnt == 5 : cnt >= 5) return true;

        cnt = 1; nr = r + 1; nc = c - 1;
        while (nr < G && nc >= 0 && b[nr][nc] == color) { cnt++; nr++; nc--; }
        nr = r - 1; nc = c + 1;
        while (nr >= 0 && nc < G && b[nr][nc] == color) { cnt++; nr--; nc++; }
        if (color == BLACK ? cnt == 5 : cnt >= 5) return true;

        return false;
    }

    public static boolean isForbidden(int[][] b, int r, int c) {
        b[r][c] = BLACK;
        boolean overline = false;
        int fours = 0, threes = 0;

        int[] dirs = {
            checkDir(b, r, c, 1, 0),
            checkDir(b, r, c, 0, 1),
            checkDir(b, r, c, 1, 1),
            checkDir(b, r, c, 1, -1)
        };

        for (int st : dirs) {
            int cnt = st >> 2;
            int opens = (st & 1) + ((st >> 1) & 1);
            if (cnt > 5) { overline = true; break; }
            if (cnt == 5) continue;
            if (cnt == 4 && opens > 0) fours++;
            if (cnt == 3 && opens == 2) threes++;
        }
        b[r][c] = EMPTY;
        return overline || (fours >= 2) || (threes >= 2);
    }

    private static int checkDir(int[][] b, int r, int c, int dr, int dc) {
        int cnt = 1, opens = 0;
        int nr = r + dr, nc = c + dc;
        while (nr >= 0 && nr < G && nc >= 0 && nc < G && b[nr][nc] == BLACK) { cnt++; nr += dr; nc += dc; }
        if (nr >= 0 && nr < G && nc >= 0 && nc < G && b[nr][nc] == EMPTY) opens += 1;
        nr = r - dr; nc = c - dc;
        while (nr >= 0 && nr < G && nc >= 0 && nc < G && b[nr][nc] == BLACK) { cnt++; nr -= dr; nc -= dc; }
        if (nr >= 0 && nr < G && nc >= 0 && nc < G && b[nr][nc] == EMPTY) opens += 2;
        return (cnt << 2) | opens;
    }

    private int staticEval(int[][] b, int role) {
        int opp = 3 - role;
        double my = 0, op = 0;
        for (int r = 0; r < G; r++) {
            for (int c = 0; c < G; c++) {
                if (b[r][c] != EMPTY) continue;
                boolean hasN = false;
                outer:
                for (int dr = -2; dr <= 2; dr++) {
                    for (int dc = -2; dc <= 2; dc++) {
                        int nr = r + dr, nc = c + dc;
                        if (nr >= 0 && nr < G && nc >= 0 && nc < G && b[nr][nc] != EMPTY) {
                            hasN = true; break outer;
                        }
                    }
                }
                if (!hasN) continue;
                b[r][c] = role; my += scoreCell(b, r, c, role) * AW + CW[r][c]; b[r][c] = EMPTY;
                b[r][c] = opp;  op += scoreCell(b, r, c, opp) * DW + CW[r][c];  b[r][c] = EMPTY;
            }
        }
        return (int) (my - op);
    }

    private List<Move> getCandidates(int[][] b, int role, int maxN) {
        int opp = 3 - role;
        List<Move> moves = new ArrayList<>();
        for (int r = 0; r < G; r++) {
            for (int c = 0; c < G; c++) {
                if (b[r][c] != EMPTY) continue;
                boolean hasN = false;
                outer:
                for (int dr = -2; dr <= 2; dr++) {
                    for (int dc = -2; dc <= 2; dc++) {
                        int nr = r + dr, nc = c + dc;
                        if (nr >= 0 && nr < G && nc >= 0 && nc < G && b[nr][nc] != EMPTY) {
                            hasN = true; break outer;
                        }
                    }
                }
                if (!hasN) continue;

                b[r][c] = role;
                if (checkWin(b, r, c, role)) {
                    b[r][c] = EMPTY;
                    return Collections.singletonList(new Move(r, c, S_WIN * 10, S_WIN, 0));
                }
                int ms = scoreCell(b, r, c, role);
                b[r][c] = EMPTY;

                b[r][c] = opp;
                if (checkWin(b, r, c, opp)) {
                    b[r][c] = EMPTY;
                    return Collections.singletonList(new Move(r, c, S_WIN * 9, ms, S_WIN));
                }
                int os = scoreCell(b, r, c, opp);
                b[r][c] = EMPTY;

                int score = (int) (ms * AW + os * DW + CW[r][c]);
                moves.add(new Move(r, c, score, ms, os));
            }
        }
        if (moves.isEmpty()) return Collections.singletonList(new Move(7, 7, 0, 0, 0));
        moves.sort((a, b) -> Integer.compare(b.score, a.score));
        return moves.size() > maxN ? new ArrayList<>(moves.subList(0, maxN)) : moves;
    }

    private Move findVCF(int[][] b, int role, int depth, int maxDepth) {
        if (depth > maxDepth || System.currentTimeMillis() - startT > timeLimit * 0.75) return null;
        int opp = 3 - role;
        List<Move> threats = new ArrayList<>();
        for (int r = 0; r < G; r++) {
            for (int c = 0; c < G; c++) {
                if (b[r][c] != EMPTY) continue;
                if (role == BLACK && isForbidden(b, r, c)) continue;
                b[r][c] = role;
                if (checkWin(b, r, c, role)) { b[r][c] = EMPTY; return new Move(r, c); }
                int sc = scoreCell(b, r, c, role);
                b[r][c] = EMPTY;
                if (sc >= S_SF) threats.add(new Move(r, c, sc));
            }
        }
        if (threats.isEmpty()) return null;
        threats.sort((a, b) -> Integer.compare(b.score, a.score));

        for (Move t : threats) {
            b[t.r][t.c] = role;
            Move forced = null;
            for (int r = 0; r < G && forced == null; r++) {
                for (int c = 0; c < G && forced == null; c++) {
                    if (b[r][c] != EMPTY) continue;
                    b[r][c] = role;
                    if (checkWin(b, r, c, role)) forced = new Move(r, c);
                    b[r][c] = EMPTY;
                }
            }
            if (forced != null) {
                b[forced.r][forced.c] = opp;
                Move res = findVCF(b, role, depth + 1, maxDepth);
                b[forced.r][forced.c] = EMPTY; b[t.r][t.c] = EMPTY;
                if (res != null) return new Move(t.r, t.c);
            } else {
                b[t.r][t.c] = EMPTY; return new Move(t.r, t.c);
            }
        }
        return null;
    }

    private Move findTSS(int[][] b, int role, int depth, int maxDepth) {
        if (depth > maxDepth || System.currentTimeMillis() - startT > timeLimit * 0.78) return null;
        int opp = 3 - role;
        List<Move> threats = new ArrayList<>();
        for (int r = 0; r < G; r++) {
            for (int c = 0; c < G; c++) {
                if (b[r][c] != EMPTY) continue;
                if (role == BLACK && isForbidden(b, r, c)) continue;
                b[r][c] = role;
                if (checkWin(b, r, c, role)) { b[r][c] = EMPTY; return new Move(r, c); }
                int sc = scoreCell(b, r, c, role);
                b[r][c] = EMPTY;
                if (sc >= S_OT) threats.add(new Move(r, c, sc));
            }
        }
        if (threats.isEmpty()) return null;
        threats.sort((a, b) -> Integer.compare(b.score, a.score));

        int limit = Math.min(12, threats.size());
        for (int i = 0; i < limit; i++) {
            Move t = threats.get(i);
            b[t.r][t.c] = role;
            int winCount = 0;
            List<Move> winCells = new ArrayList<>();
            for (int r = 0; r < G; r++) {
                for (int c = 0; c < G; c++) {
                    if (b[r][c] != EMPTY) continue;
                    b[r][c] = role;
                    if (checkWin(b, r, c, role)) { winCount++; winCells.add(new Move(r, c)); }
                    b[r][c] = EMPTY;
                }
            }
            b[t.r][t.c] = EMPTY;
            if (winCount >= 2) return new Move(t.r, t.c);
            if (winCount == 1 && depth < maxDepth) {
                b[t.r][t.c] = role;
                Move oCell = winCells.get(0);
                b[oCell.r][oCell.c] = opp;
                Move res = findTSS(b, role, depth + 1, maxDepth);
                b[oCell.r][oCell.c] = EMPTY; b[t.r][t.c] = EMPTY;
                if (res != null) return new Move(t.r, t.c);
            }
        }
        return null;
    }

    private int pvs(int[][] b, int role, int depth, int alpha, int beta, int ply) {
        nodes++;
        if ((nodes & 511) == 0 && System.currentTimeMillis() - startT > timeLimit) {
            aborted = true; return 0;
        }
        if (aborted) return 0;

        int idx = (int) (zKey & (TT_SZ - 1));
        if (ttKey[idx] == zKey && ttDep[idx] >= depth) {
            int sc = ttScr[idx]; byte fl = ttFlg[idx];
            if (fl == EXACT) return sc;
            if (fl == LOWER && sc >= beta) return sc;
            if (fl == UPPER && sc <= alpha) return sc;
        }

        if (depth == 0) return staticEval(b, role);

        int wid = depth >= 6 ? 12 : (depth >= 4 ? 16 : 20);
        List<Move> cands = getCandidates(b, role, wid);

        int k1 = killer[ply * 2]; int k2 = killer[ply * 2 + 1];
        for (Move m : cands) {
            int mk = m.r * G + m.c;
            m.sortVal = m.score + hist[mk];
            if (mk == k1) m.sortVal += 1000000000000L;
            else if (mk == k2) m.sortVal += 500000000000L;
        }
        cands.sort((a, b) -> Long.compare(b.sortVal, a.sortVal));

        int opp = 3 - role;
        int best = -S_WIN * 10;
        int bestKey = -1;
        byte flag = UPPER;
        boolean pvSearch = true;

        for (Move m : cands) {
            if (role == BLACK && isForbidden(b, m.r, m.c)) continue;
            b[m.r][m.c] = role;
            zKey ^= ZB[m.r][m.c][role == BLACK ? 0 : 1];

            int score;
            if (checkWin(b, m.r, m.c, role)) {
                score = S_WIN - ply;
            } else {
                int ext = (m.ms >= S_SF && ply < MAX_PLY - 4) ? 1 : 0;
                if (pvSearch) {
                    score = -pvs(b, opp, depth - 1 + ext, -beta, -alpha, ply + 1);
                } else {
                    score = -pvs(b, opp, depth - 1 + ext, -alpha - 1, -alpha, ply + 1);
                    if (!aborted && score > alpha && score < beta) {
                        score = -pvs(b, opp, depth - 1 + ext, -beta, -alpha, ply + 1);
                    }
                }
            }

            b[m.r][m.c] = EMPTY;
            zKey ^= ZB[m.r][m.c][role == BLACK ? 0 : 1];
            if (aborted) return 0;

            if (score > best) {
                best = score; bestKey = m.r * G + m.c;
                if (score > alpha) {
                    alpha = score; flag = EXACT; pvSearch = false;
                    if (score >= beta) {
                        flag = LOWER;
                        int ki = ply * 2;
                        if (killer[ki] != bestKey) {
                            killer[ki + 1] = killer[ki]; killer[ki] = bestKey;
                        }
                        hist[bestKey] += depth * depth;
                        break;
                    }
                }
            }
        }
        if (!aborted) {
            ttKey[idx] = zKey; ttScr[idx] = best; ttDep[idx] = (byte) depth; ttFlg[idx] = flag;
        }
        return best;
    }

    private Move getOpeningBookMove(int role) {
        if (history.isEmpty()) return new Move(7, 7);
        if (history.size() == 1 && role == WHITE) {
            Move m = history.get(0);
            return new Move(m.r + (m.r >= 7 ? -1 : 1), m.c + (m.c >= 7 ? -1 : 1));
        }
        if (history.size() >= 2 && history.size() <= 5) {
            String k = serializeHistory(history);
            List<Move> cands = OPENING_BOOK.get(k);
            if (cands != null && !cands.isEmpty()) {
                List<Move> valid = new ArrayList<>();
                for (Move m : cands) {
                    if (board[m.r][m.c] == EMPTY && !(role == BLACK && isForbidden(board, m.r, m.c))) {
                        valid.add(m);
                    }
                }
                if (!valid.isEmpty()) return valid.get(new Random().nextInt(valid.size()));
            }
        }
        if (history.size() == 2 && role == BLACK) {
            Move m0 = history.get(0), m1 = history.get(1);
            if (m0.r == 7 && m0.c == 7) {
                return new Move(7 - (int) Math.signum(m1.c - 7), 7 - (int) Math.signum(m1.r - 7));
            }
        }
        if (history.size() == 3 && role == WHITE) {
            Move m2 = history.get(2);
            int[][] dirs = {{0,1},{1,0},{0,-1},{-1,0},{1,1},{-1,-1},{1,-1},{-1,1}};
            for (int[] d : dirs) {
                int nr = m2.r + d[0], nc = m2.c + d[1];
                if (nr >= 0 && nr < G && nc >= 0 && nc < G && board[nr][nc] == EMPTY) return new Move(nr, nc);
            }
        }
        return null;
    }
}