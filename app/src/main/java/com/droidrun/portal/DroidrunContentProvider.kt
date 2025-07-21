package com.droidrun.portal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONException
import org.json.JSONObject
import androidx.core.net.toUri
import android.os.Bundle
import com.droidrun.portal.model.ElementNode
import com.droidrun.portal.model.PhoneState

class DroidrunContentProvider : ContentProvider() {
    companion object {
        private const val TAG = "DroidrunContentProvider"
        private const val AUTHORITY = "com.droidrun.portal"
        private const val A11Y_TREE = 1
        private const val PHONE_STATE = 2
        private const val PING = 3
        private const val KEYBOARD_ACTIONS = 4
        private const val STATE = 5

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "a11y_tree", A11Y_TREE)
            addURI(AUTHORITY, "phone_state", PHONE_STATE)
            addURI(AUTHORITY, "ping", PING)
            addURI(AUTHORITY, "keyboard/*", KEYBOARD_ACTIONS)
            addURI(AUTHORITY, "state", STATE)
        }
    }

    override fun onCreate(): Boolean {
        Log.d(TAG, "DroidrunContentProvider created")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("result"))

        try {
            val result = when (uriMatcher.match(uri)) {
                A11Y_TREE -> getAccessibilityTree()
                PHONE_STATE -> getPhoneState()
                PING -> createSuccessResponse("pong")
                STATE -> getCombinedState()
                else -> createErrorResponse("Unknown endpoint: ${uri.path}")
            }

            cursor.addRow(arrayOf(result))

        } catch (e: Exception) {
            Log.e(TAG, "Query execution failed", e)
            cursor.addRow(arrayOf(createErrorResponse("Execution failed: ${e.message}")))
        }

        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return when (uriMatcher.match(uri)) {
            KEYBOARD_ACTIONS -> executeKeyboardAction(uri, values)
            else -> "content://$AUTHORITY/result?status=error&message=${Uri.encode("Unsupported insert endpoint: ${uri.path}")}".toUri()
        }
    }

    private fun executeKeyboardAction(uri: Uri, values: ContentValues?): Uri? {
        if (values == null) {
            return "content://$AUTHORITY/result?status=error&message=No values provided".toUri()
        }

        try {
            val action = uri.lastPathSegment ?: return "content://$AUTHORITY/result?status=error&message=No action specified".toUri()

            val result = when (action) {
                "input" -> performKeyboardInputBase64(values)
                "clear" -> performKeyboardClear()
                "key" -> performKeyboardKey(values)
                else -> "error: Unknown keyboard action: $action"
            }

            // Encode result in URI
            return if (result.startsWith("success")) {
                "content://$AUTHORITY/result?status=success&message=${Uri.encode(result)}".toUri()
            } else {
                "content://$AUTHORITY/result?status=error&message=${Uri.encode(result)}".toUri()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Keyboard action execution failed", e)
            return "content://$AUTHORITY/result?status=error&message=${Uri.encode("Execution failed: ${e.message}")}".toUri()
        }
    }

    private fun getAccessibilityTree(): String {
        val accessibilityService = DroidrunAccessibilityService.getInstance()
            ?: return createErrorResponse("Accessibility service not available")
        return try {

            val treeJson = accessibilityService.getVisibleElements().map { element ->
                buildElementNodeJson(element)
            }

            createSuccessResponse(treeJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get accessibility tree", e)
            createErrorResponse("Failed to get accessibility tree: ${e.message}")
        }
    }

    private fun buildElementNodeJson(element: ElementNode): JSONObject {
        return JSONObject().apply {
            put("index", element.overlayIndex)
            put("resourceId", element.nodeInfo.viewIdResourceName ?: "")
            put("className", element.className)
            put("text", element.text)
            put("bounds", "${element.rect.left}, ${element.rect.top}, ${element.rect.right}, ${element.rect.bottom}")

            // Recursively build children JSON
            val childrenArray = org.json.JSONArray()
            element.children.forEach { child ->
                childrenArray.put(buildElementNodeJson(child))
            }
            put("children", childrenArray)
        }
    }


    private fun getPhoneState(): String {
        val accessibilityService = DroidrunAccessibilityService.getInstance()
            ?: return createErrorResponse("Accessibility service not available")
        return try {
            val phoneState = buildPhoneStateJson(accessibilityService.getPhoneState())
            createSuccessResponse(phoneState.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get accessibility tree", e)
            createErrorResponse("Failed to get accessibility tree: ${e.message}")
        }
    }

    private fun buildPhoneStateJson(phoneState: PhoneState) =
        JSONObject().apply {
            put("currentApp", phoneState.appName)
            put("packageName", phoneState.packageName)
            put("keyboardVisible", phoneState.keyboardVisible)
            put("focusedElement", JSONObject().apply {
                val rect = Rect()
                put("text", phoneState.focusedElement?.text)
                put("className", phoneState.focusedElement?.className)
                put("resourceId", phoneState.focusedElement?.viewIdResourceName ?: "")
            })
        }

    private fun getCombinedState(): String {
        val accessibilityService = DroidrunAccessibilityService.getInstance()
            ?: return createErrorResponse("Accessibility service not available")

        return try {
            // Get accessibility tree
            val treeJson = accessibilityService.getVisibleElements().map { element ->
                buildElementNodeJson(element)
            }

            // Get phone state
            val phoneStateJson = buildPhoneStateJson(accessibilityService.getPhoneState())

            // Combine both in a single response
            val combinedState = JSONObject().apply {
                put("a11y_tree", org.json.JSONArray(treeJson))
                put("phone_state", phoneStateJson)
            }

            createSuccessResponse(combinedState.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get combined state", e)
            createErrorResponse("Failed to get combined state: ${e.message}")
        }
    }

    private fun performTextInput(values: ContentValues): String {
        val accessibilityService = DroidrunAccessibilityService.getInstance()
            ?: return "error: Accessibility service not available"
        // Get the hex-encoded text
        val hexText = values.getAsString("hex_text")
            ?: return "error: No hex_text provided"

        // Check if we should append (default is false = replace)
        val append = values.getAsBoolean("append") ?: false

        // Decode hex to actual text
        val text = try {
            hexText.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        } catch (e: Exception) {
            return "error: Invalid hex encoding: ${e.message}"
        }

        // Find the currently focused element
        val focusedNode = accessibilityService.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "error: No focused input element found"

        return try {
            val finalText = if (append) {
                // Get existing text and append to it
                val existingText = focusedNode.text?.toString() ?: ""
                existingText + text
            } else {
                // Just use the new text (replace)
                text
            }

            // Set the text using ACTION_SET_TEXT
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
            }
            val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            focusedNode.recycle()

            if (result) {
                val mode = if (append) "appended" else "set"
                "success: Text $mode - '$text'"
            } else {
                "error: Text input failed"
            }
        } catch (e: Exception) {
            focusedNode.recycle()
            "error: Text input exception: ${e.message}"
        }
    }

    private fun performKeyboardInputBase64(values: ContentValues): String {
        val keyboardIME = DroidrunKeyboardIME.getInstance()
            ?: return "error: DroidrunKeyboardIME not active or available"

        // Check if keyboard has input connection
        if (!keyboardIME.hasInputConnection()) {
            return "error: No input connection available - keyboard may not be focused on an input field"
        }

        val base64Text = values.getAsString("base64_text")
            ?: return "error: No base64_text provided"

        return try {
            if (keyboardIME.inputB64Text(base64Text)) {
                val decoded = android.util.Base64.decode(base64Text, android.util.Base64.DEFAULT)
                val decodedText = String(decoded, Charsets.UTF_8)
                "success: Base64 text input via keyboard - '$decodedText'"
            } else {
                "error: Failed to input base64 text via keyboard"
            }
        } catch (e: Exception) {
            "error: Invalid base64 encoding: ${e.message}"
        }
    }

    private fun performKeyboardClear(): String {
        val keyboardIME = DroidrunKeyboardIME.getInstance()
            ?: return "error: DroidrunKeyboardIME not active or available"

        if (!keyboardIME.hasInputConnection()) {
            return "error: No input connection available - keyboard may not be focused on an input field"
        }

        return if (keyboardIME.clearText()) {
            "success: Text cleared via keyboard"
        } else {
            "error: Failed to clear text via keyboard"
        }
    }

    private fun performKeyboardKey(values: ContentValues): String {
        val keyboardIME = DroidrunKeyboardIME.getInstance()
            ?: return "error: DroidrunKeyboardIME not active or available"

        if (!keyboardIME.hasInputConnection()) {
            return "error: No input connection available - keyboard may not be focused on an input field"
        }

        val keyCode = values.getAsInteger("key_code")
            ?: return "error: No key_code provided"

        return if (keyboardIME.sendKeyEventDirect(keyCode)) {
            "success: Key event sent via keyboard - code: $keyCode"
        } else {
            "error: Failed to send key event via keyboard"
        }
    }


    private fun createSuccessResponse(data: String): String {
        return JSONObject().apply {
            put("status", "success")
            put("data", data)
        }.toString()
    }

    private fun createErrorResponse(error: String): String {
        return JSONObject().apply {
            put("status", "error")
            put("error", error)
        }.toString()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun getType(uri: Uri): String? = null
}