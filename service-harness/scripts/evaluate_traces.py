"""Offline frozen-task quality evaluation; see TRACE.md for evidence contracts."""
import sys
from pathlib import Path

# Isolated Python (-I -S) omits the script directory. Trust only these deployed siblings.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from quality_evaluation import evaluate, hard_assertions, main

if __name__ == "__main__":
    raise SystemExit(main())
