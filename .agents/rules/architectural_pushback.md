---
name: architectural-pushback
description: Challenge risky architectural suggestions or model choices that may have fundamental flaws for the specific use case.
---
# Architectural Pushback Rule

When the user suggests using a specific tool, architecture, or AI model (e.g., "Let's use BERT for this", "Let's rewrite this in Rust"), do not blindly implement it immediately.

1. **Evaluate the Fit:** Assess whether the suggested tool fundamentally fits the strict requirements of the task. For example, using a predictive Masked Language Model (BERT) for verbatim audio transcription is risky because MLMs inherently replace words with synonyms, destroying transcription accuracy.
2. **Warn the User:** If there is a fundamental flaw, explain the flaw clearly to the user *before* writing any code.
3. **Propose Alternatives:** Offer a better, safer, or simpler alternative (e.g., "Instead of BERT, we should use a strict hardcoded dictionary to prevent hallucinations").
4. **Wait for Confirmation:** Only proceed with the user's risky suggestion if they explicitly acknowledge the downsides and insist on moving forward.
