package local.vivodoubleclick.controller;

/** A one-time network approval is allowed only after the complete dialog is identified. */
final class WifiApprovalPolicy {
    enum Action { NONE, UNCHECK_ALWAYS, ALLOW_ONCE }
    private WifiApprovalPolicy() { }
    static Action decide(boolean armed, boolean unlocked, boolean correctPackage, boolean correctTitle,
                         boolean checkboxFound, boolean alwaysChecked, boolean allowFound,
                         boolean alreadyAllowed, boolean alreadyUnchecked) {
        if (!armed || !unlocked || !correctPackage || !correctTitle || !checkboxFound || !allowFound || alreadyAllowed)
            return Action.NONE;
        if (alwaysChecked) return alreadyUnchecked ? Action.NONE : Action.UNCHECK_ALWAYS;
        return Action.ALLOW_ONCE;
    }
}
