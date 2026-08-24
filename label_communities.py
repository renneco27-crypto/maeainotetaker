import sys, json
from graphify.build import build_from_json
from graphify.cluster import score_all
from graphify.analyze import god_nodes, surprising_connections, suggest_questions
from graphify.report import generate
from pathlib import Path

extraction = json.loads(Path('graphify-out/.graphify_extract.json').read_text(encoding='utf-8'))
detection_bytes = Path('graphify-out/.graphify_detect.json').read_bytes()
detection = json.loads(detection_bytes.decode('utf-16' if detection_bytes.startswith(b'\xff\xfe') else 'utf-8'))
analysis   = json.loads(Path('graphify-out/.graphify_analysis.json').read_text(encoding='utf-8'))

G = build_from_json(extraction, root='.', directed=False)
communities = {int(k): v for k, v in analysis['communities'].items()}
cohesion = {int(k): v for k, v in analysis['cohesion'].items()}
tokens = {'input': extraction.get('input_tokens', 0), 'output': extraction.get('output_tokens', 0)}

# Community labels based on content analysis
labels = {
    0: "Note DAO & Repository",
    1: "Note Detail UI & ViewModel",
    2: "Recording Foreground Service",
    3: "Segment DAO & Repository",
    4: "Audio Playback (ExoPlayer)",
    5: "Silero VAD Detector",
    6: "Recording Screen UI & Transcript",
    7: "Whisper STT Engine (JNI)",
    8: "Recording ViewModel",
    9: "Whisper JNI Bridge (C++)",
    10: "Note List UI & ViewModel",
    11: "Media Recorder Manager",
    12: "Audio Capture Manager",
    13: "Main Activity & Navigation",
    14: "Room Database Setup",
    15: "Koin DI Module",
    16: "Application Class",
    17: "Theme Colors",
    18: "App Build Config",
    19: "Theme Typography",
    20: "Root Build Config",
    21: "Settings Gradle",
    22: "Asset Pack Build Config",
}

# Regenerate questions with real community labels
questions = suggest_questions(G, communities, labels)

report = generate(G, communities, cohesion, labels, analysis['gods'], analysis['surprises'], detection, tokens, '.', suggested_questions=questions)
Path('graphify-out/GRAPH_REPORT.md').write_text(report, encoding='utf-8')
Path('graphify-out/.graphify_labels.json').write_text(json.dumps({str(k): v for k, v in labels.items()}, ensure_ascii=False), encoding='utf-8')
print('Report updated with community labels')