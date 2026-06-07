package com.mnemoscape.memory.model.dto;

import java.util.List;

/** 与 ai-service 的 EntityExtractResponse 形状对应。 */
public class EntityExtractionResponse {
    private List<String> people;
    private List<String> locations;
    private List<String> objects;
    private List<String> emotionTags;

    public List<String> getPeople() { return people; }
    public void setPeople(List<String> people) { this.people = people; }

    public List<String> getLocations() { return locations; }
    public void setLocations(List<String> locations) { this.locations = locations; }

    public List<String> getObjects() { return objects; }
    public void setObjects(List<String> objects) { this.objects = objects; }

    public List<String> getEmotionTags() { return emotionTags; }
    public void setEmotionTags(List<String> emotionTags) { this.emotionTags = emotionTags; }
}
