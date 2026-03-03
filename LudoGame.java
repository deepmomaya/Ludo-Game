import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class LudoGame extends JFrame {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            LudoGame game = new LudoGame();
            game.setVisible(true);
        });
    }

    private final GameState state;
    private final BoardPanel boardPanel;
    private final HudPanel hudPanel;
    private final JLabel statusLabel;
    private final Random random = new Random();

    public LudoGame() {
        setTitle("Ludo");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(940, 980);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        state = new GameState();

        boardPanel = new BoardPanel(state);
        add(boardPanel, BorderLayout.CENTER);

        statusLabel = new JLabel();
        statusLabel.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 15f));
        add(statusLabel, BorderLayout.NORTH);

        hudPanel = new HudPanel(state, this::onDiceClicked, this::onNewGame);
        add(hudPanel, BorderLayout.SOUTH);

        updateStatus();
    }

    private void onNewGame(ActionEvent e) {
        state.reset();
        boardPanel.clearInteraction();
        hudPanel.syncFromState();
        updateStatus();
        repaint();
    }

    private void updateStatus() {
        if (state.isGameOver()) {
            statusLabel.setText(state.getPlayerName(state.getWinner()) + " wins!");
            statusLabel.setForeground(state.getPlayerColor(state.getWinner()).darker());
            return;
        }
        String phase;
        if (state.getPhase() == GameState.Phase.AWAIT_ROLL) {
            phase = "Roll your dice";
        } else if (state.getPhase() == GameState.Phase.AWAIT_MOVE) {
            phase = "Pick a piece to move";
        } else {
            phase = "Moving…";
        }

        String extra = state.getHint();
        String suffix = (extra != null && extra.trim().length() > 0) ? (" • " + extra) : "";

        statusLabel.setText(state.getPlayerName(state.getCurrentPlayer())
                + " turn • " + phase + " • Last roll: " + state.getLastRoll() + suffix);
        statusLabel.setForeground(state.getPlayerColor(state.getCurrentPlayer()).darker());
    }

    private void onDiceClicked(int playerIndex) {
        if (state.isGameOver()) return;
        if (state.getPhase() != GameState.Phase.AWAIT_ROLL) return;
        if (playerIndex != state.getCurrentPlayer()) return;

        state.clearHint();
        hudPanel.setDiceEnabled(false);
        state.setPhase(GameState.Phase.ROLLING);
        updateStatus();

        int finalRoll = random.nextInt(6) + 1;
        hudPanel.rollDiceAnimated(playerIndex, finalRoll, () -> {
            onRollResolved(finalRoll);
        });
    }

    private void onRollResolved(int roll) {
        state.setLastRoll(roll);

        if (roll == 6) {
            state.incrementConsecutiveSixes();
        } else {
            state.resetConsecutiveSixes();
        }

        if (state.getConsecutiveSixes() >= 3) {
            state.resetConsecutiveSixes();
            state.setHint("Three 6s in a row — turn skipped.");
            state.setPhase(GameState.Phase.AWAIT_ROLL);
            state.nextPlayer();
            boardPanel.clearInteraction();
            hudPanel.syncFromState();
            updateStatus();
            repaint();
            return;
        }

        List<Move> moves = state.computeLegalMoves(state.getCurrentPlayer(), roll);
        if (moves.isEmpty()) {
            // No move → turn passes (even on 6)
            state.setHint("No legal moves.");
            if (roll != 6) state.resetConsecutiveSixes();
            state.setPhase(GameState.Phase.AWAIT_ROLL);
            state.nextPlayer();
            boardPanel.clearInteraction();
            hudPanel.syncFromState();
            updateStatus();
            repaint();
            return;
        }

        state.setPhase(GameState.Phase.AWAIT_MOVE);
        state.setHint(moves.size() == 1 ? "Auto-move available." : "Choose a highlighted piece.");
        if (moves.size() == 1) {
            boardPanel.clearInteraction();
            performMoveAnimated(moves.get(0), roll);
        } else {
            boardPanel.setSelectableMoves(moves, move -> performMoveAnimated(move, roll));
            hudPanel.syncFromState();
            updateStatus();
            repaint();
        }
    }

    private void performMoveAnimated(Move move, int roll) {
        if (state.isGameOver()) return;
        state.setPhase(GameState.Phase.ANIMATING);
        state.clearHint();
        boardPanel.clearInteraction();
        hudPanel.syncFromState();
        updateStatus();

        state.animateMove(move, () -> {
            MoveResolution res = state.resolveLanding(move, roll);
            boardPanel.clearInteraction();

            if (state.isGameOver()) {
                hudPanel.syncFromState();
                updateStatus();
                repaint();
                return;
            }

            boolean extraTurn = res.extraTurn;
            if (res.capture) {
                state.setHint("Captured! Extra turn.");
            } else if (res.reachedFinish) {
                state.setHint("Home! Extra turn.");
            } else if (roll == 6) {
                state.setHint("Rolled a 6 — extra turn.");
            } else {
                state.clearHint();
            }
            if (!extraTurn) {
                state.resetConsecutiveSixes();
                state.nextPlayer();
            }

            state.setPhase(GameState.Phase.AWAIT_ROLL);
            hudPanel.syncFromState();
            updateStatus();
            repaint();
        }, boardPanel::repaint);
    }

    // ---------- MODEL ----------

    static class Piece {
        // progress: -1 = in yard, 0..51 = main track (relative to player's start), 52..56 = home lane, 56 = finished
        int progress = -1;

        boolean isInYard() {
            return progress == -1;
        }

        boolean isFinished() {
            return progress == 56;
        }
    }

    static class Player {
        final List<Piece> pieces = new ArrayList<>();
    }

    static class GameState {
        static final int NUM_PLAYERS = 4;
        static final int PIECES_PER_PLAYER = 4;
        static final int TRACK_LEN = 52;
        // Number of visible home-lane cells (per color)
        static final int HOME_LEN = 5; // progress 52..56 (5 cells), 56 = finished

        enum Phase {AWAIT_ROLL, ROLLING, AWAIT_MOVE, ANIMATING}

        private static final String[] PLAYER_NAMES = {"Red", "Green", "Yellow", "Blue"};
        private static final Color[] PLAYER_COLORS = {
                new Color(220, 45, 45),
                new Color(30, 150, 70),
                new Color(235, 195, 35),
                new Color(45, 110, 220)
        };

        // Global track indexes (0..51) where each player starts (in our geometry).
        // These correspond to the first track cell next to each home.
        private static final int[] START_GLOBAL = {0, 13, 26, 39};

        // Safe squares (no capture). Common "star" style: each start and the square 8 ahead of it.
        private static final Set<Integer> SAFE_GLOBAL = new HashSet<>();
        static {
            for (int s : START_GLOBAL) {
                SAFE_GLOBAL.add(s);
                SAFE_GLOBAL.add((s + 8) % TRACK_LEN);
            }
        }

        private final List<Player> players = new ArrayList<>();
        private int currentPlayer = 0;
        private int lastRoll = 0;
        private boolean gameOver = false;
        private int winner = -1;
        private Phase phase = Phase.AWAIT_ROLL;
        private int consecutiveSixes = 0;
        private String hint = "";

        // Animation support
        private javax.swing.Timer animTimer;

        GameState() {
            initPlayers();
        }

        void reset() {
            if (animTimer != null) animTimer.stop();
            players.clear();
            initPlayers();
            currentPlayer = 0;
            lastRoll = 0;
            gameOver = false;
            winner = -1;
            phase = Phase.AWAIT_ROLL;
            consecutiveSixes = 0;
            hint = "";
        }

        private void initPlayers() {
            for (int i = 0; i < NUM_PLAYERS; i++) {
                Player p = new Player();
                for (int j = 0; j < PIECES_PER_PLAYER; j++) {
                    p.pieces.add(new Piece());
                }
                players.add(p);
            }
        }

        int getCurrentPlayer() {
            return currentPlayer;
        }

        int getLastRoll() {
            return lastRoll;
        }

        void setLastRoll(int lastRoll) {
            this.lastRoll = lastRoll;
        }

        Phase getPhase() {
            return phase;
        }

        void setPhase(Phase phase) {
            this.phase = phase;
        }

        int getConsecutiveSixes() {
            return consecutiveSixes;
        }

        void incrementConsecutiveSixes() {
            consecutiveSixes++;
        }

        void resetConsecutiveSixes() {
            consecutiveSixes = 0;
        }

        String getHint() {
            return hint;
        }

        void setHint(String hint) {
            this.hint = hint != null ? hint : "";
        }

        void clearHint() {
            this.hint = "";
        }

        boolean isGameOver() {
            return gameOver;
        }

        int getWinner() {
            return winner;
        }

        void nextPlayer() {
            currentPlayer = (currentPlayer + 1) % NUM_PLAYERS;
        }

        List<Player> getPlayers() {
            return players;
        }

        String getPlayerName(int playerIndex) {
            return PLAYER_NAMES[playerIndex];
        }

        Color getPlayerColor(int playerIndex) {
            return PLAYER_COLORS[playerIndex];
        }

        int getStartGlobal(int playerIndex) {
            return START_GLOBAL[playerIndex];
        }

        boolean isSafeGlobal(int globalIndex) {
            return SAFE_GLOBAL.contains(globalIndex);
        }

        boolean isOnTrack(Piece piece) {
            return piece.progress >= 0 && piece.progress <= 51;
        }

        boolean isInHomeLane(Piece piece) {
            return piece.progress >= 52 && piece.progress <= 56;
        }

        int getGlobalIndexForPiece(int playerIndex, Piece piece) {
            if (!isOnTrack(piece)) return -1;
            return (START_GLOBAL[playerIndex] + piece.progress) % TRACK_LEN;
        }

        private int countPiecesOnGlobalForPlayer(int playerIndex, int globalIndex) {
            int c = 0;
            Player p = players.get(playerIndex);
            for (Piece piece : p.pieces) {
                if (getGlobalIndexForPiece(playerIndex, piece) == globalIndex) c++;
            }
            return c;
        }

        private int countPiecesOnGlobalForOpponents(int playerIndex, int globalIndex) {
            int c = 0;
            for (int pIndex = 0; pIndex < NUM_PLAYERS; pIndex++) {
                if (pIndex == playerIndex) continue;
                Player p = players.get(pIndex);
                for (Piece piece : p.pieces) {
                    if (getGlobalIndexForPiece(pIndex, piece) == globalIndex) c++;
                }
            }
            return c;
        }

        private List<PieceRef> piecesOnGlobal(int globalIndex) {
            List<PieceRef> out = new ArrayList<>();
            for (int pIndex = 0; pIndex < NUM_PLAYERS; pIndex++) {
                Player p = players.get(pIndex);
                for (int i = 0; i < p.pieces.size(); i++) {
                    Piece piece = p.pieces.get(i);
                    if (getGlobalIndexForPiece(pIndex, piece) == globalIndex) {
                        out.add(new PieceRef(pIndex, i));
                    }
                }
            }
            return out;
        }

        private boolean landingBlockedByOpponentBlock(int playerIndex, int globalIndex) {
            // Classic mobile Ludo usually does NOT block moves on "walls";
            // any enemy stack can be captured if rules allow, so we do not block here.
            return false;
        }

        private boolean canMovePiece(int playerIndex, int pieceIndex, int roll) {
            Piece piece = players.get(playerIndex).pieces.get(pieceIndex);
            if (piece.isFinished()) return false;
            if (piece.isInYard()) {
                if (roll != 6) return false;
                int startGlobal = START_GLOBAL[playerIndex];
                return !landingBlockedByOpponentBlock(playerIndex, startGlobal);
            }

            int target = piece.progress + roll;
            if (target > 56) return false; // exact roll needed to finish

            if (target <= 51) {
                int targetGlobal = (START_GLOBAL[playerIndex] + target) % TRACK_LEN;
                if (landingBlockedByOpponentBlock(playerIndex, targetGlobal)) return false;
            }
            return true;
        }

        List<Move> computeLegalMoves(int playerIndex, int roll) {
            List<Move> out = new ArrayList<>();
            for (int i = 0; i < PIECES_PER_PLAYER; i++) {
                if (!canMovePiece(playerIndex, i, roll)) continue;
                Piece piece = players.get(playerIndex).pieces.get(i);
                int from = piece.progress;
                int to;
                if (piece.isInYard()) {
                    to = 0; // enter start square
                } else {
                    to = from + roll;
                }
                out.add(new Move(playerIndex, i, from, to));
            }
            return out;
        }

        void animateMove(Move move, Runnable onDone, Runnable onStep) {
            if (animTimer != null) animTimer.stop();
            Piece piece = players.get(move.playerIndex).pieces.get(move.pieceIndex);

            if (piece.isInYard()) {
                piece.progress = 0;
                if (onStep != null) onStep.run();
                if (onDone != null) onDone.run();
                return;
            }

            final int[] remaining = {move.toProgress - piece.progress};
            animTimer = new javax.swing.Timer(70, ev -> {
                if (remaining[0] <= 0) {
                    ((javax.swing.Timer) ev.getSource()).stop();
                    if (onDone != null) onDone.run();
                    return;
                }
                piece.progress++;
                remaining[0]--;
                if (onStep != null) onStep.run();
            });
            animTimer.start();
        }

        MoveResolution resolveLanding(Move move, int roll) {
            boolean capture = false;
            boolean reachedFinish = false;
            Piece moved = players.get(move.playerIndex).pieces.get(move.pieceIndex);

            if (isOnTrack(moved)) {
                int global = getGlobalIndexForPiece(move.playerIndex, moved);
                if (global >= 0 && !isSafeGlobal(global)) {
                    List<PieceRef> occupants = piecesOnGlobal(global);
                    List<PieceRef> opponents = new ArrayList<>();
                    for (PieceRef ref : occupants) {
                        if (ref.playerIndex != move.playerIndex) opponents.add(ref);
                    }
                    if (opponents.size() == 1) {
                        Piece opp = players.get(opponents.get(0).playerIndex).pieces.get(opponents.get(0).pieceIndex);
                        opp.progress = -1;
                        capture = true;
                    }
                }
            }

            if (moved.isFinished()) {
                reachedFinish = true;
            }

            checkWin(move.playerIndex);
            boolean extraTurn = (roll == 6) || capture || reachedFinish;
            phase = Phase.AWAIT_ROLL;
            return new MoveResolution(extraTurn, capture, reachedFinish);
        }

        private void checkWin(int playerIndex) {
            Player p = players.get(playerIndex);
            for (Piece piece : p.pieces) {
                if (!piece.isFinished()) return;
            }
            gameOver = true;
            winner = playerIndex;
        }
    }

    static class PieceRef {
        final int playerIndex;
        final int pieceIndex;

        PieceRef(int playerIndex, int pieceIndex) {
            this.playerIndex = playerIndex;
            this.pieceIndex = pieceIndex;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PieceRef other = (PieceRef) o;
            return other.playerIndex == playerIndex && other.pieceIndex == pieceIndex;
        }

        @Override
        public int hashCode() {
            return playerIndex * 31 + pieceIndex;
        }
    }

    static class Move {
        final int playerIndex;
        final int pieceIndex;
        final int fromProgress;
        final int toProgress;

        Move(int playerIndex, int pieceIndex, int fromProgress, int toProgress) {
            this.playerIndex = playerIndex;
            this.pieceIndex = pieceIndex;
            this.fromProgress = fromProgress;
            this.toProgress = toProgress;
        }
    }

    static class MoveResolution {
        final boolean extraTurn;
        final boolean capture;
        final boolean reachedFinish;

        MoveResolution(boolean extraTurn, boolean capture, boolean reachedFinish) {
            this.extraTurn = extraTurn;
            this.capture = capture;
            this.reachedFinish = reachedFinish;
        }
    }

    // ---------- VIEW ----------

    static class BoardPanel extends JPanel {
        private final GameState state;
        private final Map<PieceRef, Rectangle> lastPieceHitboxes = new HashMap<>();
        private List<Move> selectableMoves = Collections.emptyList();
        private MoveSelectionListener selectionListener;

        BoardPanel(GameState state) {
            this.state = state;
            setBackground(new Color(245, 246, 250));
            setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (state.isGameOver()) return;
                    if (state.getPhase() != GameState.Phase.AWAIT_MOVE) return;
                    if (selectableMoves.isEmpty()) return;
                    PieceRef clicked = findPieceAt(e.getPoint());
                    if (clicked == null) return;
                    for (Move m : selectableMoves) {
                        if (m.playerIndex == clicked.playerIndex && m.pieceIndex == clicked.pieceIndex) {
                            if (selectionListener != null) selectionListener.onSelected(m);
                            return;
                        }
                    }
                }
            });
        }

        void setSelectableMoves(List<Move> moves, MoveSelectionListener listener) {
            this.selectableMoves = moves != null ? moves : Collections.<Move>emptyList();
            this.selectionListener = listener;
            repaint();
        }

        void clearInteraction() {
            this.selectableMoves = Collections.emptyList();
            this.selectionListener = null;
            repaint();
        }

        private PieceRef findPieceAt(Point p) {
            for (Map.Entry<PieceRef, Rectangle> e : lastPieceHitboxes.entrySet()) {
                if (e.getValue().contains(p)) return e.getKey();
            }
            return null;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            lastPieceHitboxes.clear();

            int w = getWidth() - getInsets().left - getInsets().right;
            int h = getHeight() - getInsets().top - getInsets().bottom;
            int ox = getInsets().left;
            int oy = getInsets().top;

            int size = Math.min(w, h);
            int x0 = ox + (w - size) / 2;
            int y0 = oy + (h - size) / 2;

            int cell = Math.max(20, size / 15);
            int boardPx = cell * 15;

            int bx = x0 + (size - boardPx) / 2;
            int by = y0 + (size - boardPx) / 2;

            drawBoardBackground(g2, bx, by, boardPx);

            BoardGeometry geo = new BoardGeometry(bx, by, cell);

            drawHomesAndLanes(g2, geo);
            drawTrack(g2, geo);
            drawCenter(g2, geo);
            drawPieces(g2, geo);

            g2.dispose();
        }

        private void drawBoardBackground(Graphics2D g2, int x, int y, int size) {
            g2.setColor(new Color(255, 255, 255));
            g2.fill(new RoundRectangle2D.Float(x, y, size, size, 26, 26));
            g2.setColor(new Color(215, 220, 230));
            g2.setStroke(new BasicStroke(2f));
            g2.draw(new RoundRectangle2D.Float(x + 1, y + 1, size - 2, size - 2, 26, 26));
        }

        private void drawHomesAndLanes(Graphics2D g2, BoardGeometry geo) {
            for (int p = 0; p < GameState.NUM_PLAYERS; p++) {
                Color c = state.getPlayerColor(p);
                Rectangle home = geo.homeRects[p];
                g2.setColor(tint(c, 0.20f));
                g2.fillRoundRect(home.x, home.y, home.width, home.height, 18, 18);
                g2.setColor(new Color(230, 235, 245));
                Rectangle inner = new Rectangle(home.x + geo.cell, home.y + geo.cell, home.width - geo.cell * 2, home.height - geo.cell * 2);
                g2.fillRoundRect(inner.x, inner.y, inner.width, inner.height, 18, 18);

                g2.setColor(new Color(205, 210, 225));
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(home.x, home.y, home.width, home.height, 18, 18);

                // Home "slots"
                for (int i = 0; i < GameState.PIECES_PER_PLAYER; i++) {
                    Rectangle slot = geo.yardSlots[p][i];
                    g2.setColor(new Color(250, 250, 252));
                    g2.fillOval(slot.x, slot.y, slot.width, slot.height);
                    g2.setColor(new Color(200, 205, 220));
                    g2.drawOval(slot.x, slot.y, slot.width, slot.height);
                }

                // Lane
                for (int i = 0; i < GameState.HOME_LEN; i++) {
                    Rectangle r = geo.homeLaneCells[p][i];
                    g2.setColor(tint(c, 0.12f));
                    g2.fillRect(r.x, r.y, r.width, r.height);
                    g2.setColor(new Color(210, 215, 230));
                    g2.drawRect(r.x, r.y, r.width, r.height);
                }
            }
        }

        private void drawTrack(Graphics2D g2, BoardGeometry geo) {
            for (int i = 0; i < GameState.TRACK_LEN; i++) {
                Rectangle r = geo.trackCells[i];
                Color fill = new Color(245, 246, 250);
                boolean safe = state.isSafeGlobal(i);
                if (safe) fill = new Color(235, 242, 255);

                for (int p = 0; p < GameState.NUM_PLAYERS; p++) {
                    int start = state.getStartGlobal(p);
                    int star = (start + 8) % GameState.TRACK_LEN;
                    if (i == start) {
                        fill = tint(state.getPlayerColor(p), 0.78f);
                        break;
                    }
                    if (i == star) {
                        fill = tint(state.getPlayerColor(p), 0.88f);
                        break;
                    }
                }

                g2.setColor(fill);
                g2.fillRect(r.x, r.y, r.width, r.height);
                g2.setColor(new Color(210, 215, 230));
                g2.drawRect(r.x, r.y, r.width, r.height);
            }
        }

        private void drawCenter(Graphics2D g2, BoardGeometry geo) {
            Rectangle c = geo.centerRect;
            int cx = c.x + c.width / 2;
            int cy = c.y + c.height / 2;
            int left = c.x;
            int right = c.x + c.width;
            int top = c.y;
            int bottom = c.y + c.height;

            // Background
            g2.setColor(new Color(250, 251, 255));
            g2.fillRect(c.x, c.y, c.width, c.height);

            // Inset the triangles so they don't touch the border — leave a margin
            int margin = c.width / 9;
            int innerLeft   = left   + margin;
            int innerRight  = right  - margin;
            int innerTop    = top    + margin;
            int innerBottom = bottom - margin;

            // Each triangle points toward the center from its edge, with a rounded look.
            // We use a slightly inset tip (not the exact center) for a cleaner star shape.
            int tipInset = c.width / 7; // how far the tip pulls back from center
            int tipX = cx;
            int tipY = cy;

            // --- Red: LEFT side ---
            Polygon redTri = new Polygon(
                    new int[]{innerLeft, cx - tipInset, cx - tipInset},
                    new int[]{cy,        innerTop,       innerBottom},
                    3
            );
            g2.setColor(tint(state.getPlayerColor(0), 0.30f));
            g2.fillPolygon(redTri);
            g2.setColor(state.getPlayerColor(0).darker());
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawPolygon(redTri);

            // --- Green: TOP side ---
            Polygon greenTri = new Polygon(
                    new int[]{cx,         innerRight,    innerLeft},
                    new int[]{innerTop,   cy - tipInset, cy - tipInset},
                    3
            );
            g2.setColor(tint(state.getPlayerColor(1), 0.30f));
            g2.fillPolygon(greenTri);
            g2.setColor(state.getPlayerColor(1).darker());
            g2.drawPolygon(greenTri);

            // --- Yellow: RIGHT side ---
            Polygon yellowTri = new Polygon(
                    new int[]{innerRight, cx + tipInset, cx + tipInset},
                    new int[]{cy,         innerTop,      innerBottom},
                    3
            );
            g2.setColor(tint(state.getPlayerColor(2), 0.30f));
            g2.fillPolygon(yellowTri);
            g2.setColor(state.getPlayerColor(2).darker());
            g2.drawPolygon(yellowTri);

            // --- Blue: BOTTOM side ---
            Polygon blueTri = new Polygon(
                    new int[]{cx,         innerRight,    innerLeft},
                    new int[]{innerBottom, cy + tipInset, cy + tipInset},
                    3
            );
            g2.setColor(tint(state.getPlayerColor(3), 0.30f));
            g2.fillPolygon(blueTri);
            g2.setColor(state.getPlayerColor(3).darker());
            g2.drawPolygon(blueTri);

            // Center star/diamond accent — a small regular star polygon
            int starR = c.width / 5;
            int starRInner = starR / 2;
            int pts = 4;
            int[] starX = new int[pts * 2];
            int[] starY = new int[pts * 2];
            for (int i = 0; i < pts * 2; i++) {
                double angle = Math.PI / pts * i - Math.PI / 4;
                int r = (i % 2 == 0) ? starR : starRInner;
                starX[i] = cx + (int) (r * Math.cos(angle));
                starY[i] = cy + (int) (r * Math.sin(angle));
            }
            Polygon star = new Polygon(starX, starY, pts * 2);
            g2.setColor(new Color(255, 255, 255, 220));
            g2.fillPolygon(star);
            g2.setColor(new Color(200, 205, 220));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawPolygon(star);

            // Outer border of center square
            g2.setColor(new Color(185, 190, 210));
            g2.setStroke(new BasicStroke(2f));
            g2.drawRect(c.x, c.y, c.width, c.height);
        }

        private void drawPieces(Graphics2D g2, BoardGeometry geo) {
            Map<CellKey, List<PieceRef>> byCell = new HashMap<>();
            List<Player> players = state.getPlayers();

            for (int p = 0; p < players.size(); p++) {
                Player pl = players.get(p);
                for (int i = 0; i < pl.pieces.size(); i++) {
                    Piece piece = pl.pieces.get(i);
                    Rectangle cellRect = geo.getCellRectForPiece(p, i, piece, state);
                    if (cellRect == null) continue;
                    CellKey key = new CellKey(cellRect.x, cellRect.y, cellRect.width, cellRect.height);
                    byCell.computeIfAbsent(key, k -> new ArrayList<>()).add(new PieceRef(p, i));
                }
            }

            Set<PieceRef> selectable = new HashSet<>();
            for (Move m : selectableMoves) selectable.add(new PieceRef(m.playerIndex, m.pieceIndex));

            for (Map.Entry<CellKey, List<PieceRef>> entry : byCell.entrySet()) {
                CellKey k = entry.getKey();
                Rectangle cellRect = new Rectangle(k.x, k.y, k.w, k.h);
                List<PieceRef> occ = entry.getValue();

                // Arrange up to 4 pieces nicely inside the same cell.
                List<Rectangle> slots = geo.stackSlotsInside(cellRect, occ.size());
                for (int idx = 0; idx < occ.size(); idx++) {
                    PieceRef ref = occ.get(idx);
                    Rectangle slot = slots.get(idx);
                    boolean isSelectable = selectable.contains(ref) && ref.playerIndex == state.getCurrentPlayer();

                    drawToken(g2, slot, state.getPlayerColor(ref.playerIndex), isSelectable);
                    lastPieceHitboxes.put(ref, slot);
                }
            }
        }

        private void drawToken(Graphics2D g2, Rectangle r, Color color, boolean glow) {
            int pad = Math.max(2, r.width / 10);
            int x = r.x + pad;
            int y = r.y + pad;
            int s = Math.min(r.width, r.height) - pad * 2;

            if (glow) {
                g2.setColor(new Color(255, 210, 80, 170));
                g2.setStroke(new BasicStroke(Math.max(3f, s / 10f)));
                g2.drawOval(x - 2, y - 2, s + 4, s + 4);
            }

            g2.setColor(color);
            g2.fillOval(x, y, s, s);

            g2.setColor(color.darker());
            g2.setStroke(new BasicStroke(2f));
            g2.drawOval(x, y, s, s);

            // subtle highlight
            g2.setColor(new Color(255, 255, 255, 120));
            g2.fillOval(x + s / 6, y + s / 6, s / 3, s / 3);
        }

        private static Color tint(Color c, float amount) {
            float a = Math.max(0f, Math.min(1f, amount));
            int r = (int) (c.getRed() * (1 - a) + 255 * a);
            int g = (int) (c.getGreen() * (1 - a) + 255 * a);
            int b = (int) (c.getBlue() * (1 - a) + 255 * a);
            return new Color(r, g, b);
        }

        interface MoveSelectionListener {
            void onSelected(Move move);
        }

        static class CellKey {
            final int x, y, w, h;

            CellKey(int x, int y, int w, int h) {
                this.x = x;
                this.y = y;
                this.w = w;
                this.h = h;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                CellKey other = (CellKey) o;
                return x == other.x && y == other.y && w == other.w && h == other.h;
            }

            @Override
            public int hashCode() {
                int h1 = x;
                h1 = h1 * 31 + y;
                h1 = h1 * 31 + w;
                h1 = h1 * 31 + h;
                return h1;
            }
        }

        static class BoardGeometry {
            final int bx, by, cell;
            final Rectangle[] trackCells;
            final Rectangle[] homeRects;
            final Rectangle[][] yardSlots;
            final Rectangle[][] homeLaneCells;
            final Rectangle centerRect;

            BoardGeometry(int bx, int by, int cell) {
                this.bx = bx;
                this.by = by;
                this.cell = cell;

                trackCells = buildTrackCells();
                homeRects = buildHomeRects();
                yardSlots = buildYardSlots();
                homeLaneCells = buildHomeLaneCells();
                centerRect = rectCell(6, 6, 3, 3);
            }

            private Rectangle rectCell(int gx, int gy) {
                return new Rectangle(bx + gx * cell, by + gy * cell, cell, cell);
            }

            private Rectangle rectCell(int gx, int gy, int gw, int gh) {
                return new Rectangle(bx + gx * cell, by + gy * cell, cell * gw, cell * gh);
            }

            // Global track path, index 0 is Red start. Coordinates are in 15x15 grid.
            // Clockwise loop.
            private Rectangle[] buildTrackCells() {
                Point[] pts = new Point[]{
                        // index 0 (Red start) begins at (1,6)
                        new Point(1, 6), new Point(2, 6), new Point(3, 6), new Point(4, 6), new Point(5, 6), new Point(6, 5), new Point(6, 4), new Point(6, 3), new Point(6, 2), new Point(6, 1), new Point(6, 0), new Point(7, 0), new Point(8, 0),
                        // Green start at index 13 -> (8,1)
                        new Point(8, 1), new Point(8, 2), new Point(8, 3), new Point(8, 4), new Point(8, 5), new Point(9, 6), new Point(10, 6), new Point(11, 6), new Point(12, 6), new Point(13, 6), new Point(14, 6), new Point(14, 7), new Point(14, 8),
                        // Yellow start at index 26 -> (13,8)
                        new Point(13, 8), new Point(12, 8), new Point(11, 8), new Point(10, 8), new Point(9, 8), new Point(8, 9), new Point(8, 10), new Point(8, 11), new Point(8, 12), new Point(8, 13), new Point(8, 14), new Point(7, 14), new Point(6, 14),
                        // Blue start at index 39 -> (6,13)
                        new Point(6, 13), new Point(6, 12), new Point(6, 11), new Point(6, 10), new Point(6, 9), new Point(5, 8), new Point(4, 8), new Point(3, 8), new Point(2, 8), new Point(1, 8), new Point(0, 8), new Point(0, 7), new Point(0, 6)
                };
                if (pts.length != GameState.TRACK_LEN) {
                    throw new IllegalStateException("Track must have 52 cells");
                }
                Rectangle[] out = new Rectangle[pts.length];
                for (int i = 0; i < pts.length; i++) {
                    out[i] = rectCell(pts[i].x, pts[i].y);
                }
                return out;
            }

            private Rectangle[] buildHomeRects() {
                Rectangle[] out = new Rectangle[4];
                out[0] = rectCell(0, 0, 6, 6);   // Red (top-left)
                out[1] = rectCell(9, 0, 6, 6);   // Green (top-right)
                out[2] = rectCell(9, 9, 6, 6);   // Yellow (bottom-right)
                out[3] = rectCell(0, 9, 6, 6);   // Blue (bottom-left)
                return out;
            }

            private Rectangle[][] buildYardSlots() {
                Rectangle[][] out = new Rectangle[4][GameState.PIECES_PER_PLAYER];
                for (int p = 0; p < 4; p++) {
                    Rectangle home = homeRects[p];
                    int s = Math.max(cell, (int) (cell * 1.15));
                    int gap = Math.max(6, cell / 5);
                    int startX = home.x + (home.width - (s * 2 + gap)) / 2;
                    int startY = home.y + (home.height - (s * 2 + gap)) / 2;
                    out[p][0] = new Rectangle(startX, startY, s, s);
                    out[p][1] = new Rectangle(startX + s + gap, startY, s, s);
                    out[p][2] = new Rectangle(startX, startY + s + gap, s, s);
                    out[p][3] = new Rectangle(startX + s + gap, startY + s + gap, s, s);
                }
                return out;
            }

            private Rectangle[][] buildHomeLaneCells() {
                Rectangle[][] out = new Rectangle[4][GameState.HOME_LEN];
                // Red lane: left -> center (y=7, x=1..6)
                for (int i = 0; i < GameState.HOME_LEN; i++) out[0][i] = rectCell(1 + i, 7);
                // Green lane: top -> center (x=7, y=1..6)
                for (int i = 0; i < GameState.HOME_LEN; i++) out[1][i] = rectCell(7, 1 + i);
                // Yellow lane: right -> center (y=7, x=13..8)
                for (int i = 0; i < GameState.HOME_LEN; i++) out[2][i] = rectCell(13 - i, 7);
                // Blue lane: bottom -> center (x=7, y=13..8)
                for (int i = 0; i < GameState.HOME_LEN; i++) out[3][i] = rectCell(7, 13 - i);
                return out;
            }

            Rectangle getCellRectForPiece(int playerIndex, int pieceIndex, Piece piece, GameState state) {
                if (piece.isInYard()) {
                    return yardSlots[playerIndex][pieceIndex];
                }
                if (piece.isFinished()) {
                    // Park finished tokens in the center square, offset by player quadrant
                    int s = (int) (cell * 0.65);
                    int cx = centerRect.x + centerRect.width / 2;
                    int cy = centerRect.y + centerRect.height / 2;
                    int halfQ = centerRect.width / 5;
                    // Each player gets a quadrant of the center
                    int[] qx = {-halfQ, halfQ, halfQ, -halfQ};
                    int[] qy = {-halfQ, -halfQ, halfQ, halfQ};
                    int offset = (pieceIndex % 2 == 0) ? -(cell / 6) : (cell / 6);
                    int px = cx + qx[playerIndex] + (pieceIndex < 2 ? -offset : offset);
                    int py = cy + qy[playerIndex] + (pieceIndex < 2 ? -offset : offset);
                    return new Rectangle(px - s / 2, py - s / 2, s, s);
                }
                if (state.isInHomeLane(piece)) {
                    int laneIndex = piece.progress - 52;
                    return homeLaneCells[playerIndex][laneIndex];
                }
                int global = state.getGlobalIndexForPiece(playerIndex, piece);
                if (global < 0) return null;
                return trackCells[global];
            }

            List<Rectangle> stackSlotsInside(Rectangle cellRect, int count) {
                int s = (int) (Math.min(cellRect.width, cellRect.height) * 0.86);
                int x = cellRect.x + (cellRect.width - s) / 2;
                int y = cellRect.y + (cellRect.height - s) / 2;

                if (count <= 1) return Collections.singletonList(new Rectangle(x, y, s, s));

                int s2 = (int) (s * 0.60);
                int gap = Math.max(2, s / 12);
                int total = s2 * 2 + gap;
                int sx = cellRect.x + (cellRect.width - total) / 2;
                int sy = cellRect.y + (cellRect.height - total) / 2;
                Rectangle a = new Rectangle(sx, sy, s2, s2);
                Rectangle b = new Rectangle(sx + s2 + gap, sy, s2, s2);
                Rectangle c = new Rectangle(sx, sy + s2 + gap, s2, s2);
                Rectangle d = new Rectangle(sx + s2 + gap, sy + s2 + gap, s2, s2);

                List<Rectangle> out = new ArrayList<>();
                if (count == 2) {
                    out.add(a);
                    out.add(d);
                    return out;
                }
                if (count == 3) {
                    out.add(a);
                    out.add(b);
                    out.add(c);
                    return out;
                }
                out.add(a);
                out.add(b);
                out.add(c);
                out.add(d);
                return out;
            }
        }
    }

    static class HudPanel extends JPanel {
        private final GameState state;
        private final DiceWidget[] dice = new DiceWidget[GameState.NUM_PLAYERS];
        private final JButton newGameBtn = new JButton("New Game");
        private final PlayerDiceListener diceListener;
        private final javax.swing.Timer blinkTimer;
        private boolean blinkOn = false;

        HudPanel(GameState state, PlayerDiceListener diceListener, java.util.function.Consumer<ActionEvent> newGameListener) {
            this.state = state;
            this.diceListener = diceListener;

            setLayout(new BorderLayout());
            setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            setBackground(new Color(245, 246, 250));

            JPanel row = new JPanel(new GridLayout(1, 4, 12, 0));
            row.setOpaque(false);
            for (int i = 0; i < dice.length; i++) {
                final int idx = i;
                dice[i] = new DiceWidget(
                        state.getPlayerName(i),
                        state.getPlayerColor(i),
                        new Runnable() {
                            @Override
                            public void run() {
                                diceListener.onDiceClicked(idx);
                            }
                        }
                );
                row.add(dice[i]);
            }

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
            right.setOpaque(false);
            newGameBtn.addActionListener(newGameListener::accept);
            right.add(newGameBtn);

            add(row, BorderLayout.CENTER);
            add(right, BorderLayout.EAST);

            syncFromState();

            // Blink border for the player who can roll
            blinkTimer = new javax.swing.Timer(400, e -> {
                if (state.isGameOver()) {
                    blinkOn = false;
                } else if (state.getPhase() == GameState.Phase.AWAIT_ROLL) {
                    blinkOn = !blinkOn;
                } else {
                    blinkOn = false;
                }
                for (int i = 0; i < dice.length; i++) {
                    boolean isCurrent = i == state.getCurrentPlayer();
                    dice[i].setBlinking(isCurrent && blinkOn && !state.isGameOver()
                            && state.getPhase() == GameState.Phase.AWAIT_ROLL);
                }
            });
            blinkTimer.start();
        }

        void syncFromState() {
            for (int i = 0; i < dice.length; i++) {
                boolean isCurrent = (i == state.getCurrentPlayer());
                boolean canRollNow = !state.isGameOver()
                        && state.getPhase() == GameState.Phase.AWAIT_ROLL
                        && isCurrent;
                // Keep dice colored for the whole turn of that player
                dice[i].setEnabledDice(!state.isGameOver() && isCurrent);
                // Blinking border is handled by blinkTimer
                dice[i].setBlinking(false);
            }
            setDiceEnabled(true);
        }

        void setDiceEnabled(boolean enabled) {
            for (DiceWidget d : dice) d.setInputEnabled(enabled);
        }

        void rollDiceAnimated(int playerIndex, int finalValue, Runnable onDone) {
            dice[playerIndex].rollTo(finalValue, onDone);
        }

        interface PlayerDiceListener {
            void onDiceClicked(int playerIndex);
        }
    }

    static class DiceWidget extends JComponent {
        private final String label;
        private final Color color;
        private final Runnable onClick;
        private boolean diceEnabled = false;
        private boolean inputEnabled = true;
        private int value = 1;
        private javax.swing.Timer timer;
        private boolean blinking = false;

        DiceWidget(String label, Color color, Runnable onClick) {
            this.label = label;
            this.color = color;
            this.onClick = onClick;
            setPreferredSize(new Dimension(200, 92));
            setOpaque(false);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!diceEnabled || !inputEnabled) return;
                    if (onClick != null) onClick.run();
                }
            });
        }

        void setEnabledDice(boolean enabled) {
            this.diceEnabled = enabled;
            repaint();
        }

        void setBlinking(boolean blinking) {
            this.blinking = blinking;
            repaint();
        }

        void setInputEnabled(boolean enabled) {
            this.inputEnabled = enabled;
        }

        void rollTo(int finalValue, Runnable onDone) {
            if (timer != null) timer.stop();

            long start = System.currentTimeMillis();
            timer = new javax.swing.Timer(60, e -> {
                long elapsed = System.currentTimeMillis() - start;
                value = 1 + (int) (Math.random() * 6);
                repaint();
                if (elapsed >= 800) {
                    ((javax.swing.Timer) e.getSource()).stop();
                    value = finalValue;
                    repaint();
                    if (onDone != null) onDone.run();
                }
            });
            timer.start();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            g2.setColor(new Color(255, 255, 255));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 18, 18));
            g2.setColor(diceEnabled ? color.darker() : new Color(215, 220, 230));
            float borderWidth = blinking ? 4f : 2f;
            g2.setStroke(new BasicStroke(borderWidth));
            g2.draw(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, 18, 18));

            g2.setFont(getFont().deriveFont(Font.BOLD, 13f));
            g2.setColor(new Color(55, 60, 70));
            g2.drawString(label, 12, 22);

            String hint = diceEnabled ? "Click to roll" : "";
            g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            g2.setColor(new Color(110, 116, 130));
            if (hint != null && hint.trim().length() > 0) g2.drawString(hint, 12, 40);

            int diceSize = Math.min(56, h - 18);
            int dx = w - diceSize - 14;
            int dy = (h - diceSize) / 2;

            g2.setColor(diceEnabled ? tint(color, 0.88f) : new Color(244, 245, 248));
            g2.fill(new RoundRectangle2D.Float(dx, dy, diceSize, diceSize, 14, 14));

            g2.setColor(diceEnabled ? color.darker() : new Color(210, 215, 230));
            g2.setStroke(new BasicStroke(2f));
            g2.draw(new RoundRectangle2D.Float(dx, dy, diceSize, diceSize, 14, 14));

            drawPips(g2, dx, dy, diceSize, value, diceEnabled ? new Color(35, 36, 40) : new Color(150, 155, 168));

            g2.dispose();
        }

        private static void drawPips(Graphics2D g2, int x, int y, int s, int value, Color pipColor) {
            g2.setColor(pipColor);
            int r = Math.max(5, s / 8);
            int pad = s / 5;

            int cx = x + s / 2;
            int cy = y + s / 2;
            int left = x + pad;
            int right = x + s - pad;
            int top = y + pad;
            int bottom = y + s - pad;

            java.util.function.BiConsumer<Integer, Integer> pip = (px, py) -> g2.fillOval(px - r / 2, py - r / 2, r, r);

            switch (value) {
                case 1:
                    pip.accept(cx, cy);
                    break;
                case 2:
                    pip.accept(left, top);
                    pip.accept(right, bottom);
                    break;
                case 3:
                    pip.accept(left, top);
                    pip.accept(cx, cy);
                    pip.accept(right, bottom);
                    break;
                case 4:
                    pip.accept(left, top);
                    pip.accept(right, top);
                    pip.accept(left, bottom);
                    pip.accept(right, bottom);
                    break;
                case 5:
                    pip.accept(left, top);
                    pip.accept(right, top);
                    pip.accept(cx, cy);
                    pip.accept(left, bottom);
                    pip.accept(right, bottom);
                    break;
                case 6:
                    pip.accept(left, top);
                    pip.accept(right, top);
                    pip.accept(left, cy);
                    pip.accept(right, cy);
                    pip.accept(left, bottom);
                    pip.accept(right, bottom);
                    break;
                default:
                    pip.accept(cx, cy);
                    break;
            }
        }

        private static Color tint(Color c, float amount) {
            float a = Math.max(0f, Math.min(1f, amount));
            int r = (int) (c.getRed() * (1 - a) + 255 * a);
            int g = (int) (c.getGreen() * (1 - a) + 255 * a);
            int b = (int) (c.getBlue() * (1 - a) + 255 * a);
            return new Color(r, g, b);
        }
    }
}