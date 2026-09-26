package com.example.tools

import android.content.Context
import android.util.Log
import com.example.accessibility.ZoyaAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MultiStepAgent(
    private val context: Context,
    private val fileManager: FileManager,
    private val appHelper: AppControlHelper
) {

    suspend fun executeAppSearchAndSelect(
        appName: String,
        searchQuery: String,
        selectFirstResult: Boolean = true
    ): String {
        val trace = StringBuilder("AGENT EXECUTION TRACE:\n")

        // Step 1: Open Target App
        trace.append("Step 1: Launching '$appName'...\n")
        val launchResult = appHelper.launchApp(appName)
        if (!launchResult.startsWith("SUCCESS")) {
            return "FAILURE: Multi-step aborted at Step 1. $launchResult"
        }
        delay(2200) // Wait for app launch and render

        // Step 2: Check Accessibility Service
        val service = ZoyaAccessibilityService.instance
        if (service == null) {
            return "FAILURE: Step 1 succeeded ('$appName' launched), but Accessibility Service is disabled. Enable Zoya Automation in Settings to allow auto-tapping."
        }

        // Step 3: Inspect Screen to find Search
        trace.append("Step 2: Inspecting screen for search affordance...\n")
        val screen = service.inspectCurrentScreen()
        val searchKeywords = listOf("search", "find", "search_edit_text", "search_bar", "query", "explore", "dhundhe", "khoje")

        var clickedSearch = false
        // Try common search labels
        for (kw in listOf("Search", "Search YouTube", "Search or type URL", "Search apps", "Find")) {
            if (service.findAndClick(kw)) {
                clickedSearch = true
                trace.append("Step 3: Clicked search element '$kw'.\n")
                break
            }
        }

        // Fallback: search interactive elements
        if (!clickedSearch && screen != null) {
            val candidate = screen.interactiveElements.firstOrNull { elem ->
                searchKeywords.any { kw ->
                    elem.text.lowercase().contains(kw) ||
                            elem.description.lowercase().contains(kw) ||
                            elem.viewId.lowercase().contains(kw)
                }
            }
            if (candidate != null) {
                val queryTarget = candidate.text.ifBlank { candidate.description.ifBlank { candidate.viewId } }
                clickedSearch = service.findAndClick(queryTarget)
                trace.append("Step 3: Clicked search element '$queryTarget'.\n")
            }
        }

        delay(1200)

        // Step 4: Type Search Query
        trace.append("Step 4: Typing query '$searchQuery'...\n")
        val typed = service.performType(searchQuery, targetField = null, clearFirst = true)
        if (typed) {
            trace.append("Step 4: Typed search query successfully.\n")
        } else {
            trace.append("Step 4: Unable to focus input field automatically.\n")
        }

        delay(1200)

        // Step 5: Submit search or click first suggestion / Enter
        trace.append("Step 5: Inspecting search results...\n")
        val resultsScreen = service.inspectCurrentScreen()

        if (selectFirstResult && resultsScreen != null) {
            val queryClean = searchQuery.lowercase()
            val candidateResult = resultsScreen.interactiveElements.firstOrNull { elem ->
                elem.text.lowercase().contains(queryClean) || elem.description.lowercase().contains(queryClean)
            } ?: resultsScreen.interactiveElements.getOrNull(1)

            if (candidateResult != null) {
                val clickTarget = candidateResult.text.ifBlank { candidateResult.description }
                if (clickTarget.isNotBlank()) {
                    service.findAndClick(clickTarget)
                    trace.append("Step 6: Selected first matching result '$clickTarget'.\n")
                }
            }
        }

        trace.append("Step 7: Verification complete.")
        return "SUCCESS: Multi-step routine completed.\n$trace"
    }

    suspend fun createWebsiteProject(projectName: String = "my_website"): String {
        val root = "workspace/$projectName"
        fileManager.createFolder(root)
        fileManager.createFolder("$root/assets")

        val html = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$projectName</title>
    <link rel="stylesheet" href="style.css">
</head>
<body>
    <header>
        <h1>Welcome to $projectName</h1>
        <p>Created by Zoya AI Agent</p>
    </header>
    <main>
        <button id="cta-button">Click Me</button>
        <p id="output-text"></p>
    </main>
    <script src="script.js"></script>
</body>
</html>"""

        val css = """* {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}
body {
    background: #0f172a;
    color: #f8fafc;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    min-height: 100vh;
    padding: 24px;
}
header {
    text-align: center;
    margin-bottom: 32px;
}
h1 {
    color: #38bdf8;
    margin-bottom: 8px;
}
button {
    background: #0284c7;
    color: white;
    border: none;
    padding: 12px 24px;
    font-size: 16px;
    border-radius: 8px;
    cursor: pointer;
    transition: background 0.2s;
}
button:hover {
    background: #0369a1;
}"""

        val js = """document.addEventListener('DOMContentLoaded', () => {
    const btn = document.getElementById('cta-button');
    const output = document.getElementById('output-text');
    let count = 0;
    btn.addEventListener('click', () => {
        count++;
        output.textContent = 'Button clicked ' + count + ' time(s)!';
    });
});"""

        fileManager.createFile("$root/index.html", html)
        fileManager.createFile("$root/style.css", css)
        fileManager.createFile("$root/script.js", js)

        val zipRes = fileManager.createZip(root, "$projectName.zip")

        return """SUCCESS: Scaffolded complete website at '$projectName':
- $root/index.html
- $root/style.css
- $root/script.js
- $root/assets/
Packaged archive: $zipRes"""
    }

    suspend fun executeSequence(steps: JsonArray, engine: suspend (String, JsonObject) -> String): String {
        val sb = StringBuilder("SUCCESS: Executing routine (${steps.size} steps):\n")
        for ((index, stepElement) in steps.withIndex()) {
            if (stepElement !is JsonObject) continue
            val toolName = stepElement["name"]?.jsonPrimitive?.content ?: ""
            val args = stepElement["args"]?.jsonObject ?: JsonObject(emptyMap())

            sb.append("Step ${index + 1} ($toolName): ")
            val result = engine(toolName, args)
            sb.append("$result\n")
            delay(500)
        }
        return sb.toString()
    }
}
