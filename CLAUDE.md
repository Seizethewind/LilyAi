LilyAi — Project Instructions

Project Goal

Build LilyAi as an AI system from the ground up. The goal is to develop and train our own model, not merely wrap an existing chatbot API.

Architecture

Keep LilyAi modular and maintain clear separation of responsibilities.

- "core/" — runtime, lifecycle, identity
- "cognition/" — perception, reasoning, planning, decisions
- "model/" — tokenizer, neural network, inference, generation
- "training/" — datasets, training loop, optimization, evaluation, checkpoints
- "memory/" — working memory and long-term memory
- "knowledge/" — integration with LilyAiDataNotes
- "tools/" — plugins and tool execution
- "conversation/" — messages and context management
- "safety/" — validation and permissions

Training Environment

LilyAi will use cloud GPUs for training and experimentation. Training datasets, checkpoints, and model artifacts must be stored persistently outside temporary GPU instances.

LilyAiDataNotes is an independent website and knowledge service. LilyAi will access it through an authorized API/plugin.

Development Rules

1. Every non-Markdown file must contain 200 lines or fewer.
2. Each source file must have one clear responsibility.
3. Prefer small, reusable modules over large files.
4. Read existing files before modifying them.
5. Reuse proven libraries and existing technologies where appropriate.
6. Do not create fake implementations, placeholder logic, or unsupported claims.
7. Keep code typed, readable, testable, and documented.
8. Never expose credentials, secrets, or private user data.
9. Separate model training, inference, memory, and external knowledge.
10. Do not change architecture or add dependencies without explaining the reason.

Working Approach

Plan before implementing. Explain the purpose and dependencies of each file. Implement incrementally, test each change, and keep the architecture consistent.

Do not rush development. Prioritize correctness, maintainability, and understanding.
