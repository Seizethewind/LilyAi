LilyAi — Project Notes

«Project: LilyAi
Type: AI-powered Android assistant
Languages: Python + Kotlin
Build/CI: GitHub Actions
Primary interaction: Chat, voice, and Android overlay
Core capability: Understand user requests and, when authorized, operate the Android device through tools and screen interaction.»

---

1. Project Vision

LilyAi is an AI assistant that goes beyond conversation.

The goal is for LilyAi to be able to:

1. Understand what the user wants.
2. Determine the context of the request.
3. Decide whether external information is needed.
4. Select appropriate tools.
5. Observe the phone screen when necessary.
6. Plan multi-step tasks.
7. Interact with Android.
8. Verify that actions succeeded.
9. Recover from failed actions.
10. Remember information when appropriate.
11. Ask for confirmation before sensitive actions.
12. Explain what she is doing.

Core philosophy

«LilyAi should not merely explain how to perform a task. When authorized and technically possible, LilyAi should perform the task for the user.»

---

2. Technology Stack

Android Layer

Language: Kotlin

Responsibilities:

- Android application
- UI
- Chat interface
- Floating overlay
- Accessibility Service
- Screen interaction
- Touch actions
- App launching
- Notifications
- Permissions
- Screen capture integration
- Android lifecycle
- Background/foreground services
- Communication with Python/AI layer

---

AI / Logic Layer

Language: Python

Responsibilities:

- Prompt processing
- Context analysis
- Task planning
- Tool selection
- Memory logic
- Agent reasoning
- Web-search orchestration
- Action planning
- Result interpretation
- Error recovery
- AI model integration

Python should be treated as the agent brain, while Kotlin should be treated as the Android body.

---

3. High-Level Architecture

                         ┌─────────────────────┐
                         │       USER          │
                         └──────────┬──────────┘
                                    │
                          Text / Voice / Overlay
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │    KOTLIN APP      │
                         │  Android Interface  │
                         └──────────┬──────────┘
                                    │
                           Request / Events
                                    │
                                    ▼
                    ┌─────────────────────────────┐
                    │        PYTHON CORE          │
                    │        LilyAi Brain         │
                    └──────────────┬──────────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              │                    │                    │
              ▼                    ▼                    ▼
        ┌───────────┐       ┌────────────┐       ┌────────────┐
        │  Context  │       │   Memory   │       │  Reasoning │
        └───────────┘       └────────────┘       └────────────┘
              │                    │                    │
              └────────────────────┼────────────────────┘
                                   │
                                   ▼
                         ┌─────────────────────┐
                         │    TASK PLANNER     │
                         └──────────┬──────────┘
                                    │
                             Tool Selection
                                    │
             ┌──────────────────────┼──────────────────────┐
             │                      │                      │
             ▼                      ▼                      ▼
       ┌──────────┐           ┌──────────┐           ┌──────────┐
       │ Web Tool │           │ AI Tools │           │ Android  │
       └──────────┘           └──────────┘           │  Tools   │
                                                    └────┬─────┘
                                                         │
                                                         ▼
                                                 ┌──────────────┐
                                                 │ Kotlin Bridge│
                                                 └──────┬───────┘
                                                        │
                                       ┌────────────────┼────────────────┐
                                       │                │                │
                                       ▼                ▼                ▼
                                    Touch            Screen           Apps
                                    Input            Vision          /System
                                       │                │                │
                                       └────────────────┼────────────────┘
                                                        │
                                                        ▼
                                                  Verification
                                                        │
                                                        ▼
                                                     Result
                                                        │
                                                        ▼
                                                   LilyAi Reply

---

4. Core Architecture Layers

LilyAi should be separated into several logical layers.

Layer 1 — Input

Receives:

- Text
- Voice
- Overlay commands
- Android events
- Screen information
- Tool results

Components

InputManager
PromptReceiver
VoiceReceiver
EventReceiver

---

5. Prompt System

The Prompt System activates whenever LilyAi receives a user request.

Example:

User:
"Open YouTube and find relaxing music."

The prompt processor should convert the raw input into a structured request.

Example internal representation:

{
  "type": "task",
  "intent": "search_media",
  "target_app": "YouTube",
  "query": "relaxing music",
  "requires_device_control": true
}

The exact internal format can change during implementation.

---

6. Context Engine

Context determines what the user is talking about.

Examples:

"What is the weather?"
→ weather

"Open YouTube."
→ application_control

"Remember that I like jazz."
→ memory

"Find the latest news."
→ web_search

"Send John a message."
→ communication + device_control

Context should consider:

- Current prompt
- Previous conversation
- Current task
- Current screen
- Current application
- Memory
- Tool availability

---

7. Memory System

Memory is divided into multiple levels.

Short-Term Memory

Stores temporary information.

Examples:

- Current conversation
- Current task
- Current screen
- Recent actions
- Current application
- Temporary variables

---

Long-Term Memory

Stores information intentionally retained.

Examples:

- User preferences
- Favorite applications
- Frequently used commands
- Important dates
- User-defined facts
- Learned workflows

---

Memory Rules

LilyAi should not blindly save every conversation.

Memory should have:

Store
Retrieve
Update
Delete
Forget
Search

The user should be able to inspect and delete stored memories.

---

8. Web Search Skill

Web Search is an optional tool.

LilyAi should use it when:

- Information needs to be current.
- User explicitly asks for a search.
- Internal knowledge is insufficient.
- A task requires external information.
- A tool requires online information.

LilyAi should avoid unnecessary searches.

Example

User:
"What is 2 + 2?"

→ No web search.

User:
"What are today's latest Android news?"

→ Web search.

---

9. Tool Mastery

Tool Mastery is an internal capability allowing LilyAi to determine which tool should be used.

Example:

User request
      ↓
Understand goal
      ↓
What capability is needed?
      ↓
Select tool
      ↓
Execute tool
      ↓
Interpret result

Possible tools:

WebSearch
Memory
ScreenReader
ScreenCapture
Touch
Swipe
Type
OpenApp
Back
Home
Notification
Clipboard
Voice
FileSystem

Tools should expose standardized interfaces to the Python agent.

---

10. Android Tool Bridge

Python should not directly manipulate Android.

Instead:

Python AI
   ↓
Tool Request
   ↓
Kotlin Bridge
   ↓
Android API
   ↓
Device

Example:

{
  "tool": "tap",
  "x": 500,
  "y": 1200
}

Kotlin receives the request and performs the appropriate Android operation.

Result:

{
  "success": true,
  "action": "tap"
}

---

11. Touch System

Touch is LilyAi's physical interaction capability.

Supported actions should eventually include:

tap
double_tap
long_press
swipe
scroll
drag
type
back
home
recent_apps

Example:

tap(x, y)

swipe(
    start_x,
    start_y,
    end_x,
    end_y,
    duration
)

Important

Coordinates alone are not enough.

LilyAi needs screen/UI understanding before deciding where to touch.

---

12. Screen Understanding

This is one of the most important components.

LilyAi needs to answer:

«"What is currently on the screen?"»

Possible sources:

Accessibility Tree

Use Android accessibility information to obtain:

- Text
- Buttons
- Input fields
- Clickable elements
- UI hierarchy
- Content descriptions

Screen Capture

For visual information that isn't available through accessibility.

OCR

Extract visible text from screenshots when necessary.

Vision Model

Used when understanding the visual appearance of the screen is necessary.

---

13. Perception Pipeline

Android Screen
      │
      ├── Accessibility Tree
      │
      ├── Screenshot
      │
      └── OCR
             │
             ▼
       Perception Layer
             │
             ▼
       Structured Screen
             │
             ▼
        Python AI

Example:

{
  "app": "YouTube",
  "elements": [
    {
      "type": "button",
      "text": "Search",
      "clickable": true
    },
    {
      "type": "button",
      "text": "Home",
      "clickable": true
    }
  ]
}

This is preferable to blindly guessing screen coordinates.

---

14. Task Planner

The Task Planner converts a user goal into steps.

Example:

User:
"Open YouTube and search for relaxing music."

Plan:

1. Open YouTube.
2. Inspect screen.
3. Find Search.
4. Tap Search.
5. Type "relaxing music".
6. Submit search.
7. Verify search results.
8. Report completion.

The planner should maintain task state.

Task
 ├── Goal
 ├── Current Step
 ├── Completed Steps
 ├── Failed Steps
 ├── Next Step
 └── Result

---

15. Action Executor

The Action Executor takes a planned action and converts it into a tool call.

Example:

Planner:
"Tap Search."

        ↓

Action Executor

        ↓

Screen analysis

        ↓

Find Search element

        ↓

Kotlin Tool Bridge

        ↓

Accessibility click

        ↓

Result

---

16. Verification System

LilyAi must verify important actions.

Never assume:

Action = Success

Instead:

Action
  ↓
Observe
  ↓
Compare expected state
  ↓
Success / Failure

Example:

Expected:
YouTube search screen

Actual:
YouTube home screen

→ Action failed
→ Re-plan

---

17. Error Recovery

If an action fails, LilyAi should attempt recovery.

Example:

Tap Search
    ↓
Failed
    ↓
Re-read screen
    ↓
Search for alternative UI
    ↓
Try another method
    ↓
Verify

The system should limit retries to prevent infinite loops.

Example:

MAX_RETRIES = 3

After failure:

«"I couldn't complete that action."»

---

18. Safety Layer

Every action should be classified.

Safe

Examples:

- Open an app
- Search
- Scroll
- Read information
- Navigate

Potentially impactful

Examples:

- Send message
- Delete file
- Post content
- Modify settings

Require confirmation when appropriate.

Sensitive

Examples:

- Financial transactions
- Purchases
- Password/security changes
- Account deletion
- Destructive operations

Require explicit user confirmation.

---

19. Emergency Stop

The user must always be able to stop LilyAi.

The overlay should provide:

┌─────────────────────┐
│      LilyAi         │
│                     │
│   Performing task   │
│                     │
│      [ STOP ]       │
└─────────────────────┘

Stopping LilyAi should:

1. Cancel the current task.
2. Stop pending actions.
3. Prevent additional tool calls.
4. Return control to the user.

---

20. Overlay System

The overlay is LilyAi's persistent Android interface.

Possible functions:

- Open LilyAi
- Show current task
- Show status
- Voice input
- Stop current task
- Show confirmation
- Display short responses

Example states:

IDLE
LISTENING
THINKING
SEARCHING
PLANNING
ACTING
VERIFYING
WAITING_CONFIRMATION
ERROR
COMPLETED

---

21. Example Agent State Machine

              ┌─────────┐
              │  IDLE   │
              └────┬────┘
                   │
                Prompt
                   │
                   ▼
             ┌───────────┐
             │ UNDERSTAND│
             └─────┬─────┘
                   │
                   ▼
              ┌─────────┐
              │  PLAN   │
              └────┬────┘
                   │
                   ▼
              ┌─────────┐
              │  ACT    │
              └────┬────┘
                   │
                   ▼
             ┌──────────┐
             │ VERIFY   │
             └────┬─────┘
                  / \
             FAIL/   \SUCCESS
                /     \
               ▼       ▼
           RECOVER   COMPLETE
               │
               └──→ PLAN

---

22. Python Project Structure

Suggested initial structure:

python/
│
├── lilyai/
│   ├── __init__.py
│   │
│   ├── core/
│   │   ├── agent.py
│   │   ├── reasoning.py
│   │   ├── context.py
│   │   └── state.py
│   │
│   ├── prompt/
│   │   ├── processor.py
│   │   └── parser.py
│   │
│   ├── memory/
│   │   ├── manager.py
│   │   ├── short_term.py
│   │   └── long_term.py
│   │
│   ├── planner/
│   │   ├── planner.py
│   │   ├── task.py
│   │   └── executor.py
│   │
│   ├── perception/
│   │   ├── screen.py
│   │   ├── ocr.py
│   │   └── ui_parser.py
│   │
│   ├── tools/
│   │   ├── registry.py
│   │   ├── web.py
│   │   ├── android.py
│   │   └── memory.py
│   │
│   ├── safety/
│   │   ├── policy.py
│   │   ├── permissions.py
│   │   └── confirmation.py
│   │
│   └── communication/
│       └── bridge.py
│
└── tests/

---

23. Kotlin Project Structure

Suggested Android structure:

android/
│
├── app/
│
├── ui/
│   ├── MainActivity.kt
│   ├── ChatActivity.kt
│   └── OverlayManager.kt
│
├── accessibility/
│   └── LilyAccessibilityService.kt
│
├── screen/
│   ├── ScreenCapture.kt
│   └── ScreenState.kt
│
├── actions/
│   ├── TouchController.kt
│   ├── GestureController.kt
│   ├── AppLauncher.kt
│   └── InputController.kt
│
├── bridge/
│   ├── PythonBridge.kt
│   ├── ToolRequest.kt
│   └── ToolResult.kt
│
├── permissions/
│   └── PermissionManager.kt
│
├── notifications/
│   └── NotificationManager.kt
│
└── settings/
    └── SettingsManager.kt

The exact package structure can change as the project grows.

---

24. Python ↔ Kotlin Communication

A clear communication protocol is required.

Example request:

{
  "id": "action-001",
  "type": "tool_request",
  "tool": "tap",
  "arguments": {
    "x": 500,
    "y": 1200
  }
}

Kotlin response:

{
  "id": "action-001",
  "type": "tool_result",
  "success": true,
  "result": null
}

For screen information:

{
  "id": "screen-001",
  "type": "screen_state",
  "app": "YouTube",
  "elements": []
}

The protocol should eventually support:

request
response
event
error
cancel
confirmation

---

25. Tool Registry

Python should maintain a central tool registry.

Example:

ToolRegistry
│
├── web_search
├── memory_search
├── memory_store
├── screen_read
├── screenshot
├── tap
├── swipe
├── type
├── open_app
├── back
└── home

Each tool should define:

Name
Description
Arguments
Permission Level
Execution Method
Result Format

This allows LilyAi to discover available tools instead of hardcoding every action into the reasoning system.

---

26. GitHub Repository

Suggested repository:

LilyAi/
│
├── android/
├── python/
├── tests/
├── docs/
│
├── .github/
│   └── workflows/
│       ├── android.yml
│       └── python.yml
│
├── PROJECT_NOTES.md
├── README.md
├── LICENSE
└── .gitignore

---

27. GitHub Actions

GitHub Actions should automatically validate the project.

Android Workflow

Push
 ↓
Checkout
 ↓
Setup JDK
 ↓
Setup Android SDK
 ↓
Run Gradle tests
 ↓
Build APK
 ↓
Upload APK artifact

Python Workflow

Push
 ↓
Checkout
 ↓
Setup Python
 ↓
Install dependencies
 ↓
Lint
 ↓
Run unit tests

---

28. CI Goals

Every push should ideally check:

✓ Python syntax
✓ Python tests
✓ Python lint
✓ Kotlin compilation
✓ Kotlin tests
✓ Android build

Pull requests should not be merged if the core build is broken.

---

29. Secrets

API keys must never be committed into Git.

Bad:

API_KEY = "actual-key-here"

Use:

GitHub Secrets
Environment Variables
Android secure configuration

The repository should contain only placeholders/documentation.

---

30. Development Phases

Do not attempt to build the complete autonomous assistant immediately.

Phase 1 — Basic LilyAi

Build:

- Android UI
- Chat
- Python AI connection
- Basic prompt processing
- Basic response

Goal:

«User can talk to LilyAi.»

---

Phase 2 — Memory

Implement:

- Short-term context
- Long-term memory
- Save
- Retrieve
- Delete

Goal:

«LilyAi can remember information.»

---

Phase 3 — Tools

Implement:

- Tool registry
- Web search
- Tool selection
- Tool execution
- Tool results

Goal:

«LilyAi can use external capabilities.»

---

Phase 4 — Android Bridge

Implement:

- Kotlin ↔ Python communication
- Tool requests
- Tool results
- Android commands

Goal:

«Python can request Android actions.»

---

Phase 5 — Accessibility

Implement:

- Accessibility Service
- UI tree reading
- Clickable element detection
- Text extraction

Goal:

«LilyAi can understand Android UI structure.»

---

Phase 6 — Touch

Implement:

- Tap
- Swipe
- Scroll
- Type
- Back
- Home
- App launching

Goal:

«LilyAi can physically interact with Android.»

---

Phase 7 — Screen Perception

Implement:

- Screenshot capture
- OCR
- Screen analysis
- Vision integration

Goal:

«LilyAi can understand visual interfaces.»

---

Phase 8 — Task Planning

Implement:

- Goal manager
- Multi-step planning
- Action execution
- Task state
- Verification

Goal:

«LilyAi can complete multi-step tasks.»

---

Phase 9 — Recovery

Implement:

- Failed action detection
- Retry
- Re-planning
- Alternative actions
- Maximum retry limits

Goal:

«LilyAi can recover from unexpected UI changes.»

---

Phase 10 — Safety

Implement:

- Permission levels
- Confirmation system
- Sensitive action detection
- Emergency stop
- Action logging

Goal:

«LilyAi can operate safely.»

---

31. Example Complete Workflow

User says:

«"Open YouTube and find a video about Python programming."»

Step 1 — Prompt

Prompt received.

Step 2 — Context

Intent:
Application interaction + search

Step 3 — Planning

1. Open YouTube
2. Inspect screen
3. Find search
4. Tap search
5. Enter query
6. Submit
7. Verify results

Step 4 — Tool Selection

open_app
screen_read
tap
type
tap
screen_read

Step 5 — Execution

Kotlin performs the Android operations.

Step 6 — Verification

LilyAi checks whether search results appeared.

Step 7 — Result

«"Done. I searched YouTube for Python programming."»

---

32. Important Design Principle

LilyAi should operate in this cycle:

OBSERVE
   ↓
UNDERSTAND
   ↓
PLAN
   ↓
ACT
   ↓
VERIFY
   ↓
RECOVER if necessary
   ↓
REPORT

This should be considered the fundamental agent loop.

---

33. What LilyAi Must Not Do

LilyAi should not:

- Execute sensitive actions without appropriate confirmation.
- Claim an action succeeded without verification.
- Store everything permanently.
- Perform unlimited retries.
- Hide what she is doing from the user.
- Continue acting after the user presses Stop.
- Expose private information unnecessarily.
- Put API secrets into the repository.

---

34. Future Capabilities

Potential future systems:

Voice Wake Word
Voice Conversation
Notification Understanding
Calendar
Email
Maps
File Management
Camera Understanding
App Automation
Custom User Workflows
Plugin System
Multi-Agent Tools
Offline AI
Personalized Commands
Automation/Scheduling

These should be added only after the core agent architecture is stable.

---

35. Current Missing Components

The original LilyAi design contained:

✓ Prompt
✓ Context
✓ Memory
✓ Web Search
✓ Tool Mastery
✓ Touch

The expanded architecture adds:

✓ Screen Perception
✓ Accessibility/UI Understanding
✓ Reasoning
✓ Task Planning
✓ Action Execution
✓ Verification
✓ Error Recovery
✓ Safety
✓ Permissions
✓ Goal Management
✓ Android/Python Bridge
✓ Overlay
✓ Agent State Machine
✓ CI/CD
✓ Testing

---

36. MVP Definition

The first usable LilyAi version should NOT attempt to control every Android application.

The MVP should demonstrate:

User
 ↓
Prompt
 ↓
LilyAi understands request
 ↓
LilyAi plans task
 ↓
LilyAi requests Android action
 ↓
Kotlin executes action
 ↓
LilyAi observes result
 ↓
LilyAi verifies result
 ↓
LilyAi responds

A successful MVP could be:

«"Open YouTube and search for cats."»

If LilyAi can reliably perform that task from beginning to end, the fundamental architecture is working.

---

37. Long-Term Goal

The ultimate LilyAi architecture should resemble:

                 ┌─────────────────────┐
                 │       LILYAI        │
                 │      AI AGENT       │
                 └──────────┬──────────┘
                            │
             ┌──────────────┼──────────────┐
             │              │              │
          PERCEIVE        THINK          REMEMBER
             │              │              │
             ▼              ▼              ▼
          Screen          Reasoning       Memory
          Context         Planning        Knowledge
             │              │              │
             └──────────────┼──────────────┘
                            │
                           ACT
                            │
              ┌─────────────┼─────────────┐
              │             │             │
            Android        Web           Tools
              │             │             │
              └─────────────┼─────────────┘
                            │
                         VERIFY
                            │
                            ▼
                         LEARN*

"*" Learning should be controlled and should not mean silently changing the model itself.

---

38. Development Rule

When implementing a new capability, always answer these questions:

1. What does LilyAi need to understand?
2. What tool does she need?
3. What permissions are required?
4. How does Python request the action?
5. How does Kotlin execute it?
6. How does LilyAi know it succeeded?
7. What happens if it fails?
8. Does the user need confirmation?
9. What information should be remembered?
10. How will this feature be tested?

If these questions cannot be answered, the feature is not ready for implementation.

---

39. Project North Star

«LilyAi is an AI assistant with a brain, perception, memory, tools, and a body.

Python provides the brain and agent logic.

Kotlin provides the Android body and device capabilities.

GitHub Actions provides automated building and testing.

The agent loop connects everything:

Observe → Understand → Plan → Act → Verify → Recover → Respond.»

This is the core architecture that all future LilyAi features should follow.
