package com.example.snakegame;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Random;

/**
 * Classic Snake game drawn on a SurfaceView.
 * Swipe to change direction, eat the red food to grow, avoid walls and your own tail.
 */
public class SnakeView extends SurfaceView implements Runnable, SurfaceHolder.Callback {

    private static final int COLUMNS = 20;
    private static final long START_DELAY_MS = 200;
    private static final long MIN_DELAY_MS = 70;
    private static final int SWIPE_MIN_DISTANCE = 40;

    private static final int STATE_READY = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_GAME_OVER = 2;

    // Directions
    private static final int UP = 0, RIGHT = 1, DOWN = 2, LEFT = 3;

    private final SurfaceHolder holder;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Random random = new Random();
    private final SharedPreferences prefs;

    private Thread gameThread;
    private volatile boolean running;
    private volatile boolean surfaceReady;

    // Board geometry, computed when the surface size is known
    private int rows;
    private float cellSize;
    private float boardTop;
    private float boardLeft;

    // Game state (guarded by "this")
    private final ArrayList<int[]> snake = new ArrayList<>();
    private int direction = RIGHT;
    private int nextDirection = RIGHT;
    private int foodX, foodY;
    private int score;
    private int bestScore;
    private int state = STATE_READY;
    private long delayMs = START_DELAY_MS;

    private float touchStartX, touchStartY;

    public SnakeView(Context context) {
        super(context);
        holder = getHolder();
        holder.addCallback(this);
        prefs = context.getSharedPreferences("snake", Context.MODE_PRIVATE);
        bestScore = prefs.getInt("best", 0);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        setFocusable(true);
    }

    // ---------- Lifecycle ----------

    public void resume() {
        running = true;
        gameThread = new Thread(this);
        gameThread.start();
    }

    public void pause() {
        running = false;
        if (gameThread != null) {
            try {
                gameThread.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            gameThread = null;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        synchronized (this) {
            cellSize = (float) width / COLUMNS;
            float hudHeight = cellSize * 3;
            rows = (int) ((height - hudHeight) / cellSize);
            boardLeft = 0;
            boardTop = height - rows * cellSize;
            textPaint.setTextSize(cellSize * 1.2f);
            if (state != STATE_PLAYING) {
                newGame();
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
    }

    // ---------- Game loop ----------

    @Override
    public void run() {
        long lastStep = System.currentTimeMillis();
        while (running) {
            long now = System.currentTimeMillis();
            synchronized (this) {
                if (state == STATE_PLAYING && now - lastStep >= delayMs) {
                    step();
                    lastStep = now;
                }
            }
            draw();
            try {
                Thread.sleep(16);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void newGame() {
        snake.clear();
        int startY = rows / 2;
        for (int i = 0; i < 4; i++) {
            snake.add(new int[]{COLUMNS / 2 - i, startY});
        }
        direction = RIGHT;
        nextDirection = RIGHT;
        score = 0;
        delayMs = START_DELAY_MS;
        placeFood();
    }

    private void placeFood() {
        while (true) {
            int x = random.nextInt(COLUMNS);
            int y = random.nextInt(rows);
            if (!isOnSnake(x, y)) {
                foodX = x;
                foodY = y;
                return;
            }
        }
    }

    private boolean isOnSnake(int x, int y) {
        for (int[] part : snake) {
            if (part[0] == x && part[1] == y) {
                return true;
            }
        }
        return false;
    }

    private void step() {
        direction = nextDirection;
        int[] head = snake.get(0);
        int x = head[0];
        int y = head[1];
        switch (direction) {
            case UP:    y--; break;
            case DOWN:  y++; break;
            case LEFT:  x--; break;
            case RIGHT: x++; break;
        }

        boolean eating = x == foodX && y == foodY;
        // The tail moves away this step unless we are growing, so it is not an obstacle
        int[] tail = snake.get(snake.size() - 1);
        boolean hitsTail = !eating && tail[0] == x && tail[1] == y;

        if (x < 0 || y < 0 || x >= COLUMNS || y >= rows || (isOnSnake(x, y) && !hitsTail)) {
            gameOver();
            return;
        }

        snake.add(0, new int[]{x, y});
        if (eating) {
            score++;
            delayMs = Math.max(MIN_DELAY_MS, delayMs - 5);
            if (snake.size() == COLUMNS * rows) {
                gameOver();
                return;
            }
            placeFood();
        } else {
            snake.remove(snake.size() - 1);
        }
    }

    private void gameOver() {
        state = STATE_GAME_OVER;
        if (score > bestScore) {
            bestScore = score;
            prefs.edit().putInt("best", bestScore).apply();
        }
    }

    // ---------- Drawing ----------

    private void draw() {
        if (!surfaceReady) {
            return;
        }
        Canvas canvas = holder.lockCanvas();
        if (canvas == null) {
            return;
        }
        try {
            synchronized (this) {
                drawGame(canvas);
            }
        } finally {
            holder.unlockCanvasAndPost(canvas);
        }
    }

    private void drawGame(Canvas canvas) {
        canvas.drawColor(Color.rgb(17, 24, 32));

        // Board
        paint.setColor(Color.rgb(27, 38, 49));
        canvas.drawRect(boardLeft, boardTop, boardLeft + COLUMNS * cellSize, boardTop + rows * cellSize, paint);

        float pad = cellSize * 0.08f;
        float radius = cellSize * 0.25f;

        // Food
        paint.setColor(Color.rgb(231, 76, 60));
        canvas.drawCircle(boardLeft + (foodX + 0.5f) * cellSize, boardTop + (foodY + 0.5f) * cellSize,
                cellSize * 0.4f, paint);

        // Snake
        for (int i = 0; i < snake.size(); i++) {
            int[] part = snake.get(i);
            paint.setColor(i == 0 ? Color.rgb(46, 204, 113) : Color.rgb(39, 174, 96));
            rect.set(boardLeft + part[0] * cellSize + pad, boardTop + part[1] * cellSize + pad,
                    boardLeft + (part[0] + 1) * cellSize - pad, boardTop + (part[1] + 1) * cellSize - pad);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        // HUD
        float hudY = boardTop / 2 + textPaint.getTextSize() / 3;
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(getContext().getString(R.string.score, score), cellSize, hudY, textPaint);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(getContext().getString(R.string.best, bestScore), getWidth() - cellSize, hudY, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // Overlays
        if (state == STATE_READY) {
            drawMessage(canvas, getContext().getString(R.string.tap_to_start));
        } else if (state == STATE_GAME_OVER) {
            drawMessage(canvas, getContext().getString(R.string.game_over) + "\n"
                    + getContext().getString(R.string.score, score) + "\n"
                    + getContext().getString(R.string.tap_to_restart));
        }
    }

    private void drawMessage(Canvas canvas, String message) {
        paint.setColor(Color.argb(170, 0, 0, 0));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        String[] lines = message.split("\n");
        float lineHeight = textPaint.getTextSize() * 1.6f;
        float y = getHeight() / 2f - (lines.length - 1) * lineHeight / 2f;
        for (String line : lines) {
            canvas.drawText(line, getWidth() / 2f, y, textPaint);
            y += lineHeight;
        }
    }

    // ---------- Input ----------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getX();
                touchStartY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - touchStartX;
                float dy = event.getY() - touchStartY;
                boolean isSwipe = Math.abs(dx) > SWIPE_MIN_DISTANCE || Math.abs(dy) > SWIPE_MIN_DISTANCE;
                synchronized (this) {
                    if (snake.isEmpty()) {
                        // Board not laid out yet
                    } else if (state != STATE_PLAYING) {
                        if (state == STATE_GAME_OVER) {
                            newGame();
                        }
                        state = STATE_PLAYING;
                    } else if (isSwipe) {
                        int wanted;
                        if (Math.abs(dx) > Math.abs(dy)) {
                            wanted = dx > 0 ? RIGHT : LEFT;
                        } else {
                            wanted = dy > 0 ? DOWN : UP;
                        }
                        // Can't reverse straight into yourself
                        if ((wanted + 2) % 4 != direction) {
                            nextDirection = wanted;
                        }
                    }
                }
                performClick();
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
