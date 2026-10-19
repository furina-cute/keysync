package com.devoid.keysync.domain

import android.view.KeyEvent
import android.view.MotionEvent
import com.devoid.keysync.model.EventInjector
import com.devoid.keysync.model.KeyMap
import com.devoid.keysync.model.MultiModeTouchHandler

class TapModeTouchHandler(private val eventInjector: EventInjector) : MultiModeTouchHandler {
    override fun handleTouchEvent(
        keyEvent: KeyEvent,
        isPressed: Boolean,
        pointerId: Int,
        keyMap: KeyMap
    ): Boolean {
        if (keyEvent.action == MotionEvent.ACTION_DOWN) {
            val endPos = keyMap.end ?: keyMap.position
            eventInjector.injectPointer(pointerId, keyMap.position, endPos)
            eventInjector.releasePointer(pointerId)
            return !isPressed
        }
        return isPressed
    }
}

class HoldModeTouchHandler(private val eventInjector: EventInjector) : MultiModeTouchHandler {
    override fun handleTouchEvent(
        keyEvent: KeyEvent,
        isPressed: Boolean,
        pointerId: Int,
        keyMap: KeyMap
    ): Boolean {
        return if (keyEvent.action == MotionEvent.ACTION_DOWN) {
            val endPos = keyMap.end ?: keyMap.position
            eventInjector.injectPointer(pointerId, keyMap.position, endPos)
            true
        }else if (keyEvent.action == MotionEvent.ACTION_UP){
            eventInjector.releasePointer(pointerId)
            false
        }else
            isPressed
    }
}

class MixedModeTouchHandler(private val eventInjector: EventInjector) : MultiModeTouchHandler {
    private var lastKeyDownMap = hashMapOf<Int, Long>()
    private val minDifference = 200
    override fun handleTouchEvent(
        keyEvent: KeyEvent,
        isPressed: Boolean,
        pointerId: Int,
        keyMap: KeyMap
    ): Boolean {
        if (keyEvent.action == MotionEvent.ACTION_DOWN) {
            lastKeyDownMap[pointerId] = System.currentTimeMillis()
            if (isPressed) {
                eventInjector.releasePointer(pointerId)
            } else {
                val endPos = keyMap.end ?: keyMap.position
                eventInjector.injectPointer(pointerId, keyMap.position, endPos)
            }
            return !isPressed
        } else if (keyEvent.action == MotionEvent.ACTION_UP) {
            if (!isPressed)
                return false
            val difference = System.currentTimeMillis() - (lastKeyDownMap[pointerId] ?: 0)
            if (difference > minDifference) {
//               eventInjector.injectPointer(pointerId, keyMap.position, keyMap.end!!)
                eventInjector.releasePointer(pointerId)
                return false
            }
        }
        return isPressed
    }

}