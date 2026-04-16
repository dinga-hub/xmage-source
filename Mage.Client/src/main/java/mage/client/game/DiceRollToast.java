package mage.client.game;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Path2D;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Floating, non-modal dialog showing dice roll results.
 * Appears on each roll; user can drag and close. Re-opens on next roll.
 */
public class DiceRollToast {

    private static DiceRollToast instance;

    private static final Pattern DIE_INFO = Pattern.compile(
            "\\[Roll a die\\] (.+?) rolled (?:(\\d+)|a )d(\\d+)"
    );
    private static final Pattern DIE_RESULT = Pattern.compile(
            "results?: (\\[?[^(\\n]+?)(?:\\s*\\(|$)"
    );
    private static final Pattern PLANAR_INFO = Pattern.compile(
            "\\[Roll a planar die\\] (.+?) rolled (\\w+)"
    );

    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm");
    private static final Color BG = new Color(68, 68, 72);
    private static final Color BG_DARK = new Color(55, 55, 58);
    private static final Color BORDER_COL = new Color(100, 100, 105);

    // -------------------------------------------------------------------------
    // Die shape panel
    // -------------------------------------------------------------------------
    private static class DiePanel extends JPanel {
        private int sides = 20;
        private String result = "";
        private Color resultColor = new Color(255, 220, 60);

        DiePanel() {
            setPreferredSize(new Dimension(140, 110));
            setOpaque(false);
        }

        void update(int sides, String result, Color color) {
            this.sides = sides;
            this.result = result;
            this.resultColor = color;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int cx = w / 2, cy = h / 2;
            int r = Math.min(w, h) / 2 - 10;

            // Fill + stroke
            Shape shape = buildShape(sides, cx, cy, r);
            g2.setColor(new Color(82, 82, 88));
            g2.fill(shape);
            g2.setColor(new Color(160, 160, 170));
            g2.setStroke(new BasicStroke(2f));
            g2.draw(shape);

            // Result text — size depends on length
            boolean simple = result.length() <= 3;
            int fontSize = simple ? 32 : (result.length() <= 6 ? 18 : 13);
            Font font = new Font("Arial", Font.BOLD, fontSize);
            g2.setFont(font);
            g2.setColor(resultColor);
            FontMetrics fm = g2.getFontMetrics();
            int tx = cx - fm.stringWidth(result) / 2;
            int ty = cy + fm.getAscent() / 2 - fm.getDescent() / 2;
            g2.drawString(result, tx, ty);

            g2.dispose();
        }

        private Shape buildShape(int sides, int cx, int cy, int r) {
            switch (sides) {
                case 4: return triangle(cx, cy, r);
                case 6: return square(cx, cy, r);
                case 8: return diamond(cx, cy, r);
                default: return polygon(Math.min(sides, 10), cx, cy, r);
            }
        }

        private Shape triangle(int cx, int cy, int r) {
            // Equilateral triangle pointing up
            Path2D p = new Path2D.Float();
            p.moveTo(cx, cy - r);
            p.lineTo(cx - r * Math.cos(Math.toRadians(30)), cy + r / 2.0);
            p.lineTo(cx + r * Math.cos(Math.toRadians(30)), cy + r / 2.0);
            p.closePath();
            return p;
        }

        private Shape square(int cx, int cy, int r) {
            int half = (int) (r / Math.sqrt(2));
            return new Rectangle(cx - half, cy - half, half * 2, half * 2);
        }

        private Shape diamond(int cx, int cy, int r) {
            Path2D p = new Path2D.Float();
            p.moveTo(cx, cy - r);
            p.lineTo(cx + r, cy);
            p.lineTo(cx, cy + r);
            p.lineTo(cx - r, cy);
            p.closePath();
            return p;
        }

        private Shape polygon(int n, int cx, int cy, int r) {
            Path2D p = new Path2D.Float();
            for (int i = 0; i < n; i++) {
                double angle = 2 * Math.PI * i / n - Math.PI / 2;
                double x = cx + r * Math.cos(angle);
                double y = cy + r * Math.sin(angle);
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            p.closePath();
            return p;
        }
    }

    // -------------------------------------------------------------------------
    // Dialog
    // -------------------------------------------------------------------------
    private final JDialog dialog;
    private final DiePanel diePanel;
    private final JLabel lblInfo;
    private final JLabel lblSource;
    private final DefaultListModel<String> historyModel = new DefaultListModel<>();

    private DiceRollToast(Window parent) {
        dialog = new JDialog(parent, "Dice Roll", Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
        dialog.setAlwaysOnTop(true);
        dialog.setResizable(false);

        diePanel = new DiePanel();

        lblInfo = new JLabel("", SwingConstants.CENTER);
        lblInfo.setFont(new Font("Arial", Font.PLAIN, 12));
        lblInfo.setForeground(new Color(200, 200, 205));

        lblSource = new JLabel("", SwingConstants.CENTER);
        lblSource.setFont(new Font("Arial", Font.ITALIC, 11));
        lblSource.setForeground(new Color(160, 160, 165));

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBackground(BG);
        topPanel.setBorder(new EmptyBorder(8, 12, 6, 12));
        for (JComponent c : new JComponent[]{lblInfo, diePanel, lblSource}) {
            c.setAlignmentX(Component.CENTER_ALIGNMENT);
            topPanel.add(c);
            topPanel.add(Box.createVerticalStrut(2));
        }

        // History
        JList<String> historyList = new JList<>(historyModel);
        historyList.setFont(new Font("Arial", Font.PLAIN, 11));
        historyList.setBackground(BG_DARK);
        historyList.setForeground(new Color(185, 185, 190));
        historyList.setFixedCellHeight(16);

        JScrollPane historyScroll = new JScrollPane(historyList);
        historyScroll.setPreferredSize(new Dimension(220, 72));
        historyScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(BORDER_COL),
                "History", 0, 0,
                new Font("Arial", Font.PLAIN, 10), new Color(180, 180, 185)
        ));
        historyScroll.setBackground(BG_DARK);
        historyScroll.getViewport().setBackground(BG_DARK);

        JPanel main = new JPanel(new BorderLayout(0, 0));
        main.setBackground(BG);
        main.setBorder(BorderFactory.createLineBorder(BORDER_COL, 1));
        main.add(topPanel, BorderLayout.CENTER);
        main.add(historyScroll, BorderLayout.SOUTH);

        dialog.setContentPane(main);
        dialog.pack();
    }

    private void updateRoll(int sides, String dieLabel, String result,
                            String player, String source) {
        Color col = resultColor(sides, result);
        diePanel.update(sides, result, col);
        lblInfo.setText(dieLabel + "   \u2014   " + player);
        lblSource.setText(source.isEmpty() ? " " : source);

        String entry = TIME_FMT.format(new Date())
                + "  " + dieLabel + " \u2192 " + result
                + "  (" + player + ")";
        historyModel.insertElementAt(entry, 0);
        if (historyModel.size() > 10) historyModel.remove(10);

        dialog.setVisible(true);
        dialog.toFront();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------
    public static void showIfDiceRoll(String message, Component parent) {
        if (message == null || !message.startsWith("[Roll a")) return;
        final String[] parsed = parseDiceMessage(message);
        if (parsed == null) return;

        SwingUtilities.invokeLater(() -> {
            Window parentWindow = SwingUtilities.getWindowAncestor(parent);
            if (parentWindow == null) {
                parentWindow = mage.client.MageFrame.getInstance();
            }
            if (parentWindow == null) return;

            if (instance == null) {
                instance = new DiceRollToast(parentWindow);
                // Center on screen
                instance.dialog.setLocationRelativeTo(null);
            }

            int sides = Integer.parseInt(parsed[0]);
            instance.updateRoll(sides, parsed[1], parsed[2], parsed[3], parsed[4]);
        });
    }

    // -------------------------------------------------------------------------
    // Parsing — returns {sides, dieLabel, result, player, source} or null
    // -------------------------------------------------------------------------
    private static String[] parseDiceMessage(String message) {
        Matcher mInfo = DIE_INFO.matcher(message);
        if (mInfo.find()) {
            String player = stripHtml(mInfo.group(1));
            String count  = mInfo.group(2);
            String sides  = mInfo.group(3);
            String dieLabel = (count != null ? count : "1") + "d" + sides;

            Matcher mResult = DIE_RESULT.matcher(message);
            String result = mResult.find() ? mResult.group(1).trim() : "?";
            int paren = result.indexOf('(');
            if (paren >= 0) result = result.substring(0, paren).trim();
            result = stripHtml(result);

            String source = "";
            int srcIdx = message.lastIndexOf("(source: ");
            if (srcIdx >= 0) {
                source = stripHtml(message.substring(srcIdx + 9).replace(")", "").trim());
            }

            return new String[]{sides, dieLabel, result, player, source};
        }

        Matcher mPlanar = PLANAR_INFO.matcher(message);
        if (mPlanar.find()) {
            String player  = stripHtml(mPlanar.group(1));
            String outcome = mPlanar.group(2);
            return new String[]{"0", "Planar Die", outcome, player, ""};
        }

        return null;
    }

    private static String stripHtml(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", "").trim();
    }

    private static Color resultColor(int sides, String result) {
        if ("CHAOS".equalsIgnoreCase(result)) return new Color(255, 90, 90);
        if ("PLANAR".equalsIgnoreCase(result)) return new Color(100, 220, 100);
        if ("BLANK".equalsIgnoreCase(result))  return new Color(160, 160, 160);
        // Numerical: highlight nat max
        try {
            int val = Integer.parseInt(result);
            if (sides > 0 && val == sides) return new Color(255, 215, 0); // nat max = gold
            if (sides > 0 && val == 1)     return new Color(220, 80, 80); // nat 1 = red
        } catch (NumberFormatException ignored) {}
        return new Color(230, 230, 235); // default: light gray
    }
}
