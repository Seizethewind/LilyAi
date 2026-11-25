# LilyAi

An AI system built from the ground up: our own model, trained by us, not a wrapper around an existing chatbot API. Project rules are in `CLAUDE.md`.

## Status

Early scaffold. The runtime, configuration, conversation, memory, tool registry and safety checks work. The tokenizer, model, training, reasoning, planning, decisions and the LilyAiDataNotes client are stubs that raise `NotImplementedError`. Nothing has been tested yet.

## Layout

- `core/`: runtime, lifecycle, identity, errors, logging
- `cognition/`: perception, reasoning, planning, decisions, prompts
- `model/`: tokenizer, layers, sampling, language model, inference
- `training/`: data, training loop, optimization, evaluation, checkpoints
- `memory/`: working and long-term memory
- `knowledge/`: LilyAiDataNotes integration
- `tools/`: tool registry, execution, plugins
- `conversation/`: messages and context
- `safety/`: validation and permissions
- `scripts/`: training and evaluation entry points

Every file has one responsibility and stays under 200 lines.

## Install

Requires Python 3.10 or newer. The model and training code use PyTorch; the rest is standard library only.

```
pip install -e ".[dev]"
```

On a GPU machine, first install the PyTorch build that matches its CUDA version (see pytorch.org), then run the command above.

## Run

`python main.py` starts the terminal chat loop. It reports the first unimplemented step until the model exists.

## Test

`pytest`. Each test is added together with the code it checks.
