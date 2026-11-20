package io.github.fantuani.draftcanvas;

final class QuestionImage {
    final long id;
    final String asset;
    final double x, y, width, height;

    QuestionImage(long id, String asset, double x, double y, double width, double height) {
        this.id = id;
        this.asset = asset;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    double right() { return x + width; }
    double bottom() { return y + height; }

    boolean contains(double pointX, double pointY) {
        return pointX >= x && pointX <= right() && pointY >= y && pointY <= bottom();
    }

    boolean intersects(double left, double top, double right, double bottom) {
        return right() > left && x < right && bottom() > top && y < bottom;
    }

    QuestionImage offset(long newId, double dx, double dy) {
        return new QuestionImage(newId, asset, x + dx, y + dy, width, height);
    }

    QuestionImage scaled(double anchorX, double anchorY, double factor) {
        return new QuestionImage(id, asset, anchorX + (x - anchorX) * factor,
                anchorY + (y - anchorY) * factor, width * factor, height * factor);
    }
}
