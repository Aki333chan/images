package ovh.aurumgg.ui;

import java.util.List;

record UiPanel(String id, int priority, String title, List<String> lines) {}
