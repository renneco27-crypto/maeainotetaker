import json
import os
import re
from typing import Dict, Set, List
import Levenshtein

# Lazy load transformers to avoid slowing down startup if not used immediately
mlm_pipeline = None

class PhoneticCorrector:
    def __init__(self, vocab_path: str = None):
        if vocab_path is None:
            vocab_path = os.path.join(os.path.dirname(__file__), "custom_vocabulary.json")
            
        self.vocab_path = vocab_path
        self.phonetic_map: Dict[str, str] = {}
        self.custom_terms: Set[str] = set()
        
        self.load_vocabulary()
        
    def load_vocabulary(self):
        """Loads custom vocabulary and explicit phonetic corrections."""
        if not os.path.exists(self.vocab_path):
            print(f"[WARN] Vocabulary file not found at {self.vocab_path}")
            return
            
        try:
            with open(self.vocab_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                
            self.custom_terms = set(data.get("custom_terms", []))
            self.phonetic_map = {k.lower(): v for k, v in data.get("phonetic_corrections", {}).items()}
                
            print(f"[INFO] Loaded Strict Phonetic Dictionary: {len(self.custom_terms)} custom terms, {len(self.phonetic_map)} rules.")
        except Exception as e:
            print(f"[ERROR] Error loading vocabulary: {e}")

    def _init_mlm(self):
        global mlm_pipeline
        if mlm_pipeline is None:
            print(f"[INFO] Loading DistilBERT Multilingual Context AI (might take a moment to download first time)...")
            from transformers import pipeline
            mlm_pipeline = pipeline('fill-mask', model='distilbert-base-multilingual-cased', device=-1)

    def correct_word(self, token: str) -> str:
        """Corrects known custom terms and explicit phonetic errors while leaving valid words untouched."""
        if not token:
            return token
            
        match = re.match(r"^([^\w]*)([\w\-\'\.]+)([^\w]*)$", token, re.UNICODE)
        if not match:
            return token
            
        prefix, word, suffix = match.groups()
        lowered = word.lower()
        
        # 1. Custom terms exact/case-insensitive match
        for term in self.custom_terms:
            if lowered == term.lower():
                return f"{prefix}{term}{suffix}"
                
        # 2. Explicit phonetic dictionary replacement
        if lowered in self.phonetic_map:
            corrected = self.phonetic_map[lowered]
            return f"{prefix}{self._match_case(word, corrected)}{suffix}"

        return token

    def correct_text(self, text: str) -> str:
        """Passes transcribed text through precision vocabulary corrections."""
        if not text or not text.strip():
            return text
            
        tokens = text.split()
        corrected_tokens = [self.correct_word(token) for token in tokens]
        return " ".join(corrected_tokens)

    def _match_case(self, original: str, replacement: str) -> str:
        """Transfers capitalization from the original token to the replacement."""
        if original.isupper():
            return replacement.upper()
        elif original.istitle():
            return replacement.capitalize()
        return replacement

# Global singleton instance
corrector = PhoneticCorrector()

