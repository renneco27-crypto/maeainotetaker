import urllib.request
import os

print("Fetching comprehensive Tagalog & Cebuano wordlists from GitHub...")

urls = [
    # Top common Cebuano words
    "https://raw.githubusercontent.com/frekwencja/most-common-words-multilingual/master/data/ceb/ceb_50k.txt",
    # Top common Tagalog words
    "https://raw.githubusercontent.com/frekwencja/most-common-words-multilingual/master/data/tl/tl_50k.txt",
    # Tagalog word list
    "https://raw.githubusercontent.com/raymelon/tagalog-dictionary-scraper/master/tagalog_dict.txt"
]

output_file = os.path.join(os.path.dirname(__file__), "ph_frequency_dictionary.txt")
word_counts = {}

for url in urls:
    try:
        print(f"Downloading from {url}...")
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=10) as response:
            content = response.read().decode('utf-8', errors='ignore')
            lines = content.splitlines()
            print(f"  Got {len(lines)} lines from {url}")
            for i, line in enumerate(lines):
                parts = line.strip().split()
                if not parts:
                    continue
                word = parts[0].lower()
                if len(parts) > 1 and parts[1].isdigit():
                    count = int(parts[1])
                else:
                    # Synthetic frequency if not provided: earlier words in dict have higher freq
                    count = max(10, 50000 - i)
                word_counts[word] = max(word_counts.get(word, 0), count)
    except Exception as e:
        print(f"  Failed to fetch from {url}: {e}")

print(f"Saving {len(word_counts)} unified words to {output_file}...")
with open(output_file, "w", encoding="utf-8") as f:
    for word, count in sorted(word_counts.items(), key=lambda x: x[1], reverse=True):
        f.write(f"{word} {count}\n")

print("Dictionary build complete!")
