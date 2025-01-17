package me.aap.fermata.auto;

import static android.accessibilityservice.GestureDescription.getMaxGestureDuration;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_POINTER_UP;
import static android.view.MotionEvent.ACTION_UP;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.GestureDescription.StrokeDescription;
import android.graphics.Path;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.RequiresApi;

import java.util.Arrays;

import me.aap.utils.log.Log;

public class AccessibilityEventDispatcherService extends AccessibilityService {
	private static AccessibilityEventDispatcherService instance;
	private final Path path = new Path();
	private GestureDescription.Builder gb;
	private Pointer[] pointers = new Pointer[]{new Pointer()};

	static boolean dispatchTap(float x, float y) {
		var ds = instance;
		if ((VERSION.SDK_INT < VERSION_CODES.N) || (ds == null)) return false;
		ds.path.reset();
		ds.path.moveTo(x, y);
		var gb = new GestureDescription.Builder().addStroke(new StrokeDescription(ds.path, 0L, 1L));
		return ds.dispatchGesture(gb.build(), null, null);
	}

	static boolean dispatchScale(float x, float y, float diff) {
		var ds = instance;
		if ((VERSION.SDK_INT < VERSION_CODES.O) || (ds == null)) return false;
		var path = ds.path;
		var dur = 100L;
		if (diff > 0) {
			path.reset();
			path.moveTo(x, y);
			path.lineTo(x - diff, y);
			ds.addStroke(new StrokeDescription(path, 0L, dur, false));
			path.reset();
			path.moveTo(x + 10, y);
			path.lineTo(x + diff + 10, y);
			ds.addStroke(new StrokeDescription(path, 0L, dur, false));
		} else {
			path.reset();
			path.moveTo(x + diff, y);
			path.lineTo(x, y);
			ds.addStroke(new StrokeDescription(path, 0L, dur, false));
			path.reset();
			path.moveTo(x - diff + 10, y);
			path.lineTo(x + 10, y);
			ds.addStroke(new StrokeDescription(path, 0L, dur, false));
		}
		return ds.dispatch();
	}

	static boolean dispatchMotionEvent(MotionEvent e) {
		var ds = instance;
		if ((VERSION.SDK_INT < VERSION_CODES.O) || (ds == null)) return false;
		var pointers = ds.pointers;
		var cnt = e.getPointerCount();
		var action = e.getActionMasked();
		boolean completePrev;

		if ((cnt > 1) && (action == ACTION_MOVE)) {
			long timeDiff = e.getEventTime() - ds.pointers[0].time;
			if (timeDiff < 30L) {
				if (timeDiff < 0) ds.pointers[0].time = e.getEventTime();
				completePrev = false;
			} else {
				completePrev = true;
			}
		} else {
			completePrev = false;
		}

		if (cnt > pointers.length) {
			ds.pointers = pointers = Arrays.copyOf(pointers, cnt);
			for (int i = 0; i < cnt; i++) {
				if (pointers[i] == null) pointers[i] = new Pointer();
			}
		}

		var downTime = e.getDownTime();
		var eventTime = e.getEventTime();
		int idx;

		if ((action == ACTION_POINTER_DOWN) || (action == ACTION_POINTER_UP)) {
			action = (action == ACTION_POINTER_DOWN) ? ACTION_DOWN : ACTION_UP;
			idx = e.getActionIndex();
			cnt = idx + 1;
		} else {
			idx = 0;
		}
		for (; idx < cnt; idx++) {
			var x = e.getX(idx);
			var y = e.getY(idx);
			if ((x < 0f) || (y < 0f)) continue;
			var p = pointers[idx];
			if (!ds.buildGesture(p, downTime, eventTime, action, x, y, completePrev)) return false;
		}

		return (ds.gb == null) || ds.dispatch();
	}

	static boolean dispatchMotionEvent(long downTime, long eventTime, int action, float x, float y) {
		var ds = instance;
		if ((VERSION.SDK_INT < VERSION_CODES.O) || (ds == null)) return false;
		if ((x < 0f) || (y < 0f)) return true;
		var p = instance.pointers[0];
		return ds.buildGesture(p, downTime, eventTime, action, x, y, false) && ds.dispatch();
	}

	@RequiresApi(api = VERSION_CODES.O)
	private boolean buildGesture(Pointer p, long downTime, long eventTime, int action, float x,
															 float y, boolean completePrev) {
		switch (action) {
			case ACTION_DOWN -> {
				p.x = x;
				p.y = y;
				p.time = eventTime;
				path.reset();
				path.moveTo(x, y);
				var dur = Math.max(1L, Math.min(eventTime - downTime, getMaxGestureDuration()));
				p.sd = new StrokeDescription(path, 0L, dur, true);
				addStroke(p.sd);
			}
			case ACTION_UP -> {
				if (p.sd == null) return true;
				path.reset();
				path.moveTo(x, y);
				var dur = Math.max(1L, Math.min(eventTime - p.time, getMaxGestureDuration()));
				var sd = p.sd.continueStroke(path, 0L, dur, false);
				p.sd = null;
				addStroke(sd);
			}
			case ACTION_MOVE -> {
				path.reset();
				path.moveTo(p.x, p.y);
				path.lineTo(x, y);
				var dur = Math.max(1L, Math.min(eventTime - p.time, getMaxGestureDuration()));
				p.x = x;
				p.y = y;
				p.time = eventTime;
				StrokeDescription sd;
				if (p.sd == null) {
					p.sd = sd = new StrokeDescription(path, 0L, dur, true);
				} else if (completePrev) {
					sd = p.sd.continueStroke(path, 0L, dur, false);
					p.sd = null;
				} else {
					p.sd = sd = p.sd.continueStroke(path, 0L, dur, true);
				}
				addStroke(sd);
			}
			default -> {
				Log.d("Unable to dispatch event with action ", action);
				return false;
			}
		}
		return true;
	}

	@RequiresApi(api = VERSION_CODES.N)
	private void addStroke(StrokeDescription sd) {
		if (gb == null) gb = new GestureDescription.Builder();
		gb.addStroke(sd);
	}

	@RequiresApi(api = VERSION_CODES.N)
	private boolean dispatch() {
		var g = gb.build();
		gb = null;
		return dispatchGesture(g, null, null);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		instance = this;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		instance = null;
	}

	@Override
	public void onAccessibilityEvent(AccessibilityEvent event) {
	}

	@Override
	public void onInterrupt() {
	}

	private static final class Pointer {
		float x;
		float y;
		long time;
		StrokeDescription sd;
	}
}
