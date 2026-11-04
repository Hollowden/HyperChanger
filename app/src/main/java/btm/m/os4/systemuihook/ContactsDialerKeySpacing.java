// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook;

import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.ImageView;
import java.util.WeakHashMap;

/** Preserve key bounds and labels; trim only the theme's circular image rim. */
public final class ContactsDialerKeySpacing {
    private static final WeakHashMap<View, Drawable> decorated = new WeakHashMap<>();
    private ContactsDialerKeySpacing() {}
    public static void apply(View panelBackground) {
        // View mutation must stay on the UI thread even during asynchronous inflate.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return;
        ViewParent parent = panelBackground.getParent();
        if (parent instanceof View) visit((View) parent);
    }
    private static void visit(View view) {
        String name = view.getClass().getName();
        if (name.equals("com.android.contacts.dialpad.DialpadImageButton")) {
            ImageView key = (ImageView) view;
            Drawable current = key.getDrawable();
            if (current instanceof BitmapDrawable && current != decorated.get(view)) {
                Bitmap original = ((BitmapDrawable) current).getBitmap();
                int width=original.getWidth(), height=original.getHeight();
                // Only handle a square icon containing an opaque circular rim.
                // Stock narrow number-only assets retain their original appearance.
                if (Math.abs(width-height)>2 || width<32) return;
                int edge=original.getPixel(width/2, Math.max(1, height/20));
                if (Color.alpha(edge)<200) return;
                Bitmap trimmed=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                trimmed.setDensity(original.getDensity());
                Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                paint.setShader(new BitmapShader(original,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));
                new Canvas(trimmed).drawCircle(width/2f,height/2f,Math.min(width,height)*0.43f,paint);
                BitmapDrawable result=new BitmapDrawable(view.getResources(),trimmed);
                key.setImageDrawable(result);
                decorated.put(view,result);
            }
            return;
        }
        if (name.equals("com.android.contacts.dialpad.DialerItemView")) {
            try {
                Object value=view.getClass().getMethod("getBackgroundView").invoke(view);
                if (value instanceof View && value!=view) {
                    ((View)value).setScaleX(0.86f);
                    ((View)value).setScaleY(0.86f);
                }
            } catch (ReflectiveOperationException ignored) {}
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) visit(group.getChildAt(i));
        }
    }
}
