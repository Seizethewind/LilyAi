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
            if not text:
                continue
            try:
                print(f"lily> {runtime.handle(text)}")
            except (NotImplementedError, ValueError) as err:
                print(f"lily> [{type(err).__name__}] {err}")
    except (KeyboardInterrupt, EOFError):
        pass
    finally:
        runtime.stop()


if __name__ == "__main__":
    main()
