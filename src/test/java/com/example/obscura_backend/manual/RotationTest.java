package com.example.obscura_backend.manual;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class RotationTest {

    public static void main(String[] args) throws IOException {
        BufferedImage original = createTestImage(400, 600);
        ImageIO.write(original, "jpg", new File("test-original.jpg"));
        System.out.println("Created test-original.jpg (400x600)");

        BufferedImage rotated90CW = rotateImage(original, 90);
        ImageIO.write(rotated90CW, "jpg", new File("test-rotated-90CW.jpg"));
        System.out.println("Created test-rotated-90CW.jpg (600x400) - rotated RIGHT");

        BufferedImage rotated270CW = rotateImage(original, 270);
        ImageIO.write(rotated270CW, "jpg", new File("test-rotated-270CW.jpg"));
        System.out.println("Created test-rotated-270CW.jpg (600x400) - rotated LEFT");

        System.out.println("\nInstructies:");
        System.out.println("1. Open test-original.jpg - Je ziet 'TOP' bovenaan in portrait mode");
        System.out.println("2. Open test-rotated-90CW.jpg - 'TOP' is nu RECHTS (gedraaid naar RECHTS)");
        System.out.println("3. Open test-rotated-270CW.jpg - 'TOP' is nu LINKS (gedraaid naar LINKS)");
        System.out.println("\nAls je portrait foto's naar RECHTS staan:");
        System.out.println("-> Dan moet je LINKS draaien = 270° CW = -90° = 90° CCW");
        System.out.println("\nVoor EXIF orientation 6 (portrait), gebruik dan: rotateImage(image, 270);");
    }

    private static BufferedImage createTestImage(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.BOLD, 48));
        g.drawString("TOP", width/2 - 50, 80);

        g.drawString("BOTTOM", width/2 - 100, height - 40);

        g.setStroke(new BasicStroke(5));
        int centerX = width / 2;
        int arrowY = height / 2;
        g.drawLine(centerX, arrowY + 100, centerX, arrowY - 100);
        g.drawLine(centerX, arrowY - 100, centerX - 30, arrowY - 70);
        g.drawLine(centerX, arrowY - 100, centerX + 30, arrowY - 70);

        g.dispose();
        return img;
    }

    private static BufferedImage rotateImage(BufferedImage image, int angle) {
        int width = image.getWidth();
        int height = image.getHeight();

        int newWidth = (angle == 90 || angle == 270) ? height : width;
        int newHeight = (angle == 90 || angle == 270) ? width : height;

        BufferedImage rotated = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = rotated.createGraphics();

        AffineTransform transform = new AffineTransform();

        switch (angle) {
            case 90:
                transform.translate(height, 0);
                transform.rotate(Math.PI / 2);
                break;
            case 270:
                transform.translate(0, width);
                transform.rotate(-Math.PI / 2);
                break;
        }

        g2d.drawImage(image, transform, null);
        g2d.dispose();

        return rotated;
    }
}

