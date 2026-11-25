"""LilyAi entry point: simple terminal chat loop."""
from config import Config
from core.runtime import Runtime


def main() -> None:
    runtime = Runtime(Config())
    runtime.start()
    try:
        while True:
            text = input("you> ").strip()
            if text in {"/quit", "/exit"}:
                break
            if text:
                print(f"lily> {runtime.handle(text)}")
    except (KeyboardInterrupt, EOFError):
        pass
    finally:
        runtime.stop()


if __name__ == "__main__":
    main()
