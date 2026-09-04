# Android activity UI slice

The native Activities & cadence screen uses the accepted shared activity repository and separate activity database. It creates calendar-day cadence records, records a completion, shows completion history and recomputes due dates from the current device zone when reopened. Activity data survives process restarts. Input validation and in-flight control disabling prevent malformed input and overlapping UI actions; completion callbacks retain one idempotency key per rendered action.

The screen explicitly states that notification delivery is not connected. A displayed due date is a preview, not a notification receipt. Native notification permission/delivery, background restart/timezone hooks, and iOS J2 UI remain pending; neither full J2 nor the full Android J2 acceptance criterion is accepted by this slice alone.

The installed emulator test exercises invalid input, create, completion, history, force-stop, device timezone change and reopening history. It restores the emulator timezone in a finally block and cannot run on a physical device. The existing J1 regression and single guarded public workflow are preserved. No private inputs, new service, extra workflow or billing configuration is introduced.
