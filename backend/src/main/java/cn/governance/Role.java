package cn.governance;

public enum Role {
    CENTER_ADMIN("中心管理"), CENTER_ANALYST("中心研判"), DEPARTMENT_REPORTER("部门上报"),
    DEPARTMENT_HANDLER("部门处置"), SPECIAL_REPORTER("特殊上报");

    private final String label;
    Role(String label) { this.label = label; }
    public String label() { return label; }
    public boolean isCenterRole() { return this == CENTER_ADMIN || this == CENTER_ANALYST; }
}
