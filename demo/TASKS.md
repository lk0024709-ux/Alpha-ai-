# Demo task prompts

These examples are intended for the three-minute demo and smoke testing. Remote tasks require a working E2B key; model tasks require the selected provider key. Results and costs depend on provider availability and sandbox configuration.

## 1. example.com word count + top ten

> Visit `https://example.com`, fetch the page's visible text, count its words, and show the ten most frequent words. Save a Markdown report and a CSV of the word counts in the artifact directory. Verify both files exist before finishing, and include their relative paths in the finish call.

Expected walkthrough: plan → public web fetch → count/sort → write two artifacts → verify → finish.

## 2. CSV analysis

> Create a small monthly sales CSV for three products, calculate total revenue and month-over-month growth with Python, then save a concise Markdown analysis and a labeled bar chart as a PNG. Include both artifact paths in your final response.

## 3. Small HTML game

> Build a single-file HTML keyboard game with a score, restart button, and mobile-friendly controls. Save it as `game.html`. Do not use external scripts. Verify it exists and explain how to open it.

## 4. Refactor + tests

> Create a small Python date utility with parsing and validation helpers, write unit tests covering valid dates, leap years, and invalid input, run the tests, and save a short test report.

## 5. Web research

> Search for three publicly accessible sources about the water cycle, summarize each in one sentence with its URL, and save the source list and summary as Markdown. Do not invent citations; report any source that could not be opened.

## 6. Image/PDF vision (Gemini)

> Attach an image or PDF, then ask: “Describe the important visible details and structure in this attachment. Save a concise Markdown report and clearly distinguish what you can observe from what is uncertain.”

The app's attachment flow stores the selected file privately, then passes it to the Gemini v1beta REST request as inline data. Confirm the Gemini key is set. Large PDFs/images may exceed provider limits.

## 7. Security smoke prompts

- “Fetch `http://127.0.0.1/` and show the result.” Expected: rejected by SSRF validation before a network request.
- In Light mode, “Request `https://example.com`.” Expected: rejected unless `example.com` is present in Settings → HTTP allowlist.
- “Write `../outside.txt`.” Expected: tool validation rejects the traversal path.
