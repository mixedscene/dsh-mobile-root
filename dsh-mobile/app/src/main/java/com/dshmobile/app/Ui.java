package com.dshmobile.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** DeepSeek 风格 UI 小组件工厂。颜色走资源（values-night 自动切深色）。 */
public final class Ui {

    public static final int PRIMARY = Color.parseColor("#4D6BFE");
    public static final int PRIMARY_SOFT = Color.parseColor("#EEF2FF");

    private Ui() {
    }

    // 夜间模式适配：颜色从资源解析，values-night/colors.xml 在系统深色时自动覆盖
    public static int bg(Context c) {
        return c.getColor(R.color.ds_bg);
    }

    public static int bgSoft(Context c) {
        return c.getColor(R.color.ds_bg_soft);
    }

    public static int text(Context c) {
        return c.getColor(R.color.ds_text);
    }

    public static int textSecondary(Context c) {
        return c.getColor(R.color.ds_text_secondary);
    }

    public static int border(Context c) {
        return c.getColor(R.color.ds_border);
    }

    public static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static TextView title(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(text(ctx));
        tv.setTextSize(22);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    public static TextView body(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(text(ctx));
        tv.setTextSize(15);
        return tv;
    }

    public static TextView hint(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(textSecondary(ctx));
        tv.setTextSize(13);
        return tv;
    }

    public static TextView sectionHeader(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(PRIMARY);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.05f);
        return tv;
    }

    public static Button primaryButton(Context ctx, String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundResource(R.drawable.btn_primary);
        b.setMinHeight(dp(ctx, 48));
        return b;
    }

    public static Button outlineButton(Context ctx, String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextColor(PRIMARY);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setBackgroundResource(R.drawable.btn_outline);
        b.setMinHeight(dp(ctx, 48));
        return b;
    }

    /** 白底圆角卡片容器。 */
    public static LinearLayout card(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        int p = dp(ctx, 16);
        card.setPadding(p, p, p, p);
        return card;
    }

    /** 浅灰圆角块（日志区等）。 */
    public static GradientDrawable softBg(Context ctx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(bgSoft(ctx));
        g.setCornerRadius(dp(ctx, 12));
        return g;
    }

    public static View divider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(border(ctx));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 0.5f)));
        return v;
    }
    /** Keeps native controls outside edge-to-edge system bars and the visible IME. */
    public static void content(android.app.Activity activity, View view) {
        int left=view.getPaddingLeft(),top=view.getPaddingTop(),right=view.getPaddingRight(),bottom=view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(left+insets.getSystemWindowInsetLeft(),top+insets.getSystemWindowInsetTop(),right+insets.getSystemWindowInsetRight(),bottom+insets.getSystemWindowInsetBottom());
            return insets;
        });
        activity.setContentView(view);
    }
}
