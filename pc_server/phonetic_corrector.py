import json
import os
import re
from typing import Dict, List, Set
from symspellpy import SymSpell, Verbosity

class PhoneticCorrector:
    def __init__(self, vocab_path: str = None):
        if vocab_path is None:
            vocab_path = os.path.join(os.path.dirname(__file__), "custom_vocabulary.json")
            
        self.vocab_path = vocab_path
        self.phonetic_map: Dict[str, str] = {}
        self.custom_terms: Set[str] = set()
        
        # Initialize SymSpell with max_dictionary_edit_distance=2, prefix_length=7
        self.sym_spell = SymSpell(max_dictionary_edit_distance=2, prefix_length=7)
        
        self.load_vocabulary()
        
    def load_vocabulary(self):
        """Loads custom vocabulary, phonetic corrections, and populates SymSpell."""
        if not os.path.exists(self.vocab_path):
            print(f"[WARN] Vocabulary file not found at {self.vocab_path}")
            return
            
        try:
            with open(self.vocab_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                
            self.custom_terms = set(data.get("custom_terms", []))
            self.phonetic_map = {k.lower(): v for k, v in data.get("phonetic_corrections", {}).items()}
            
            # 1. Load Philippine frequency dictionary (39k+ words)
            ph_dict_path = os.path.join(os.path.dirname(__file__), "ph_frequency_dictionary.txt")
            if os.path.exists(ph_dict_path):
                self.sym_spell.load_dictionary(ph_dict_path, term_index=0, count_index=1, separator=" ", encoding="utf-8")
                print(f"[INFO] Loaded Philippine frequency dictionary ({ph_dict_path}).")

            # Feed words into SymSpell frequency dictionary
            # Add custom terms with high frequency count
            for term in self.custom_terms:
                self.sym_spell.create_dictionary_entry(term.lower(), 10000)
                
            # Add phonetic target words with high frequency count
            for word in self.phonetic_map.values():
                self.sym_spell.create_dictionary_entry(word.lower(), 5000)
                
            print(f"[INFO] Loaded Phonetic Dictionary: {len(self.custom_terms)} custom terms, {len(self.phonetic_map)} phonetic rules.")
        except Exception as e:
            print(f"[ERROR] Error loading vocabulary: {e}")

    def normalize_syllables(self, word: str) -> str:
        """Applies Tagalog/Bisaya vowel and consonant normalization heuristics."""
        lowered = word.lower()
        
        # Direct lookup in phonetic dictionary
        if lowered in self.phonetic_map:
            return self.phonetic_map[lowered]
            
        # Common vowel interchange heuristics (e -> i, o -> u) for sound-alike matching
        variant_e_to_i = lowered.replace("e", "i")
        if variant_e_to_i in self.phonetic_map:
            return self.phonetic_map[variant_e_to_i]
            
        variant_o_to_u = lowered.replace("o", "u")
        if variant_o_to_u in self.phonetic_map:
            return self.phonetic_map[variant_o_to_u]
            
        return lowered

    def correct_word(self, token: str) -> str:
        """Corrects a single token while preserving capitalization and leading/trailing punctuation."""
        if not token:
            return token
            
        # Extract leading/trailing punctuation
        match = re.match(r"^([^\w]*)([\w\-\'\.]+)([^\w]*)$", token, re.UNICODE)
        if not match:
            return token
            
        prefix, word, suffix = match.groups()
        
        # Check if already a known custom term
        for term in self.custom_terms:
            if word.lower() == term.lower():
                return f"{prefix}{term}{suffix}"
                
        normalized = self.normalize_syllables(word)

        # 1. Direct phonetic dictionary match
        if normalized in self.phonetic_map:
            corrected = self.phonetic_map[normalized]
            return f"{prefix}{self._match_case(word, corrected)}{suffix}"

        # Short words (length <= 3) should only be corrected via exact phonetic mapping, never fuzzy edit distance
        if len(word) <= 3:
            return token

        # 2. SymSpell fuzzy lookup (max edit distance 1)
        suggestions = self.sym_spell.lookup(word.lower(), Verbosity.TOP, max_edit_distance=1)
        if suggestions:
            best_match = suggestions[0].term
            return f"{prefix}{self._match_case(word, best_match)}{suffix}"
                
        return token

    def correct_text(self, text: str) -> str:
        """Passes full transcribed text through syllable & phonetic correction pipeline."""
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
