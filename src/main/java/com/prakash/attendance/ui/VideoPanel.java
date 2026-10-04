package com.prakash.attendance.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/** Shows the latest camera frame, scaled to fit while keeping its shape. */
public class VideoPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private transient BufferedImage image;
    private String message = "Starting camera...";

    public VideoPanel() {
        setBackground(new Color(0x15141A));
        setPreferredSize(new Dimension(660, 500));
    }

    public void setImage(BufferedImage image) {
        this.image = image;
        this.message = null;
        repaint();
    }

    public void setMessage(String message) {
        this.message = message;
        this.image = null;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        if (image != null) {
            double scale = Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
            int w = (int) (image.getWidth() * scale);
            int h = (int) (image.getHeight() * scale);
            g2.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
        } else if (message != null) {
            g2.setColor(new Color(0xC9C6D3));
            g2.setFont(getFont().deriveFont(16f));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(message, (getWidth() - fm.stringWidth(message)) / 2, getHeight() / 2);
        }
    }
}
