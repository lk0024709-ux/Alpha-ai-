package com.alphaai.app.domain.model

data class ExampleTask(val title: String, val prompt: String, val icon: String)

object Examples {
    val all = listOf(
        ExampleTask("Chart a dataset", "Fetch the latest population data for 10 countries, create a CSV and a labeled bar chart as a PNG artifact.", "▥"),
        ExampleTask("Scrape a page", "Visit example.com, count the words in its visible text, and save a short Markdown report with the top 10 words.", "⌕"),
        ExampleTask("Refactor code", "Create a small Python module for parsing dates, add tests for edge cases, run them, and summarize the result.", "⌘"),
        ExampleTask("Analyze a CSV", "Create a sample monthly sales CSV, calculate totals and growth, then write a concise analysis report.", "▤"),
        ExampleTask("Build a mini game", "Build a polished single-file HTML keyboard game with score, restart, and mobile-friendly controls.", "◇"),
        ExampleTask("Vision report", "Inspect the attached image and write a concise visual description with important details as Markdown.", "◉")
    )
}
