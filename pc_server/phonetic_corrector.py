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

    def correct_with_context(self, words_with_probs: List[Dict]) -> str:
        """
        Takes a list of {'word': str, 'prob': float}.
        Uses DistilBERT to predict low-confidence words based on context,
        picking the prediction that sounds closest to Whisper's raw guess.
        """
        # First, apply basic custom terms correction
        for w in words_with_probs:
            w['word'] = self.correct_word(w['word'])

        # Identify low confidence words to mask (prob < 0.45)
        low_conf_indices = [i for i, w in enumerate(words_with_probs) if w['prob'] < 0.45]
        
        if not low_conf_indices:
            return " ".join([w['word'] for w in words_with_probs])
            
        self._init_mlm()
        
        # We process one mask at a time for better context
        for idx in low_conf_indices:
            original_word_clean = re.sub(r"[^\w]", "", words_with_probs[idx]['word'].lower())
            if not original_word_clean:
                continue

            # Create masked sentence
            masked_sentence_parts = []
            for i, w in enumerate(words_with_probs):
                if i == idx:
                    masked_sentence_parts.append("[MASK]")
                else:
                    masked_sentence_parts.append(w['word'])
                    
            masked_sentence = " ".join(masked_sentence_parts)
            
            try:
                predictions = mlm_pipeline(masked_sentence)
                best_pred = words_with_probs[idx]['word']
                best_distance = float('inf')
                
                # Check top 5 predictions, pick the one that sounds most similar (Levenshtein distance)
                for pred in predictions:
                    pred_word_clean = re.sub(r"[^\w]", "", pred['token_str'].lower())
                    
                    # Exact match or very close phonetic edit distance
                    dist = Levenshtein.distance(original_word_clean, pred_word_clean)
                    
                    # If it's a good contextual guess and phonetically similar (distance <= 2)
                    if dist <= 2 and dist < best_distance:
                        best_distance = dist
                        best_pred = pred['token_str']
                        
                # Update word if we found a better contextual replacement
                if best_distance <= 2:
                    words_with_probs[idx]['word'] = best_pred
                else:
                    # If AI couldn't find a good phonetic match, it's likely pure noise. Drop it.
                    words_with_probs[idx]['word'] = ""
            except Exception as e:
                print(f"[WARN] Masked LM failed on sentence: {e}")
                words_with_probs[idx]['word'] = ""
                
        # Filter out empty words
        final_words = [w['word'] for w in words_with_probs if w['word'].strip() != ""]
        return " ".join(final_words).strip()

    def _match_case(self, original: str, replacement: str) -> str:
        """Transfers capitalization from the original token to the replacement."""
        if original.isupper():
            return replacement.upper()
        elif original.istitle():
            return replacement.capitalize()
        return replacement

# Global singleton instance
corrector = PhoneticCorrector()

