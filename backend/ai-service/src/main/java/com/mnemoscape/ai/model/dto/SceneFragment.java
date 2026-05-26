package com.mnemoscape.ai.model.dto;

public class SceneFragment {
    private String fragmentType;
    private String content;
    private Position3d position3d;
    private double triggerRadius;
    private boolean isDiscovered;

    public SceneFragment() {
    }

    public SceneFragment(String fragmentType, String content, Position3d position3d, double triggerRadius, boolean isDiscovered) {
        this.fragmentType = fragmentType;
        this.content = content;
        this.position3d = position3d;
        this.triggerRadius = triggerRadius;
        this.isDiscovered = isDiscovered;
    }

    public String getFragmentType() {
        return fragmentType;
    }

    public void setFragmentType(String fragmentType) {
        this.fragmentType = fragmentType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Position3d getPosition3d() {
        return position3d;
    }

    public void setPosition3d(Position3d position3d) {
        this.position3d = position3d;
    }

    public double getTriggerRadius() {
        return triggerRadius;
    }

    public void setTriggerRadius(double triggerRadius) {
        this.triggerRadius = triggerRadius;
    }

    public boolean getIsDiscovered() {
        return isDiscovered;
    }

    public void setIsDiscovered(boolean discovered) {
        isDiscovered = discovered;
    }
}
