package com.crm.travelcrm.onboarding.model;

/**
 * The ordered activation checklist shown to a freshly provisioned tenant. Declaration order IS the
 * display order. Each constant's {@link #name()} is the stable API key (persisted in
 * {@code onboarding_step_markers.step_key}, so keep it &le; 40 chars and never rename an existing
 * one without a data migration). The label/description are human-facing copy for the widget.
 */
public enum OnboardingStep {

    SET_LOGO("Add your company logo",
            "Upload your logo so it appears on quotations, vouchers and invoices."),
    INVITE_TEAM("Invite a team member",
            "Add at least one more user so your team can collaborate."),
    ADD_MASTER("Add your first hotel",
            "Create a hotel in your masters so you can start building quotations."),
    CONFIGURE_MESSAGING("Set up email or WhatsApp",
            "Configure SMTP or WhatsApp so you can send vouchers and reminders to customers."),
    CREATE_LEAD("Capture your first lead",
            "Add a lead to start filling your sales pipeline."),
    CREATE_QUOTATION("Build your first quotation",
            "Turn a lead into a priced quotation."),
    CREATE_BOOKING("Confirm your first booking",
            "Convert a quotation into a confirmed booking.");

    private final String label;
    private final String description;

    OnboardingStep(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /** Stable API key = the enum name. */
    public String getKey() {
        return name();
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }
}