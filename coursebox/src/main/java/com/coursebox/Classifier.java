package com.coursebox;

import java.util.List;

/** 按文件名 + 所在路径猜课程和格子，猜不中进「待整理」 */
public class Classifier {

    /** 类型关键词 → 默认格子名 */
    private static final String[][] CAT_KEYS = {
        {"课件PPT", "ppt", "课件", "讲义", "第1章", "第2章", "第3章", "第4章", "第5章", "第6章", "第7章", "第8章", "第9章", "ch1", "ch2", "ch3", "ch4", "ch5", "ch6", "ch7", "ch8", "ch9"},
        {"实验报告", "实验", "报告", "lab", "report"},
        {"作业", "作业", "习题", "homework", "hw"},
        {"截图", "screenshot", "截图", "screen_shot", "mmexport", "img_", "wx_camera"},
        {"笔记", "笔记", "note", ".md", "总结", "复习"}
    };

    public static class Guess {
        public Models.Course course;
        public Models.Cat cat;
    }

    /** 在课程列表里猜这份文件该去哪 */
    public static Guess guess(List<Models.Course> courses, String fileName, String parentPath, Models.Course inbox) {
        Guess g = new Guess();
        String hay = (fileName + " " + parentPath).toLowerCase();

        // 课程：文件名或路径里含课程名（或课程名去掉"大学"等前缀后的词）
        for (Models.Course c : courses) {
            if ("inbox".equals(c.id)) continue;
            String n = c.name.toLowerCase();
            if (hay.contains(n)) { g.course = c; break; }
            String short2 = n.replace("大学", "");
            if (short2.length() >= 2 && hay.contains(short2)) { g.course = c; break; }
        }
        if (g.course == null) g.course = inbox;
        g.cat = guessCat(g.course, fileName, parentPath);
        return g;
    }

    /** 在指定课程里猜格子：先按格子名匹配，再按关键词表匹配默认格子 */
    public static Models.Cat guessCat(Models.Course course, String fileName, String parentPath) {
        String hay = (fileName + " " + parentPath).toLowerCase();
        Models.Cat cat = null;
        for (Models.Cat c : course.cats) {
            String n = c.name.toLowerCase();
            if (n.length() >= 2 && hay.contains(n)) { cat = c; break; }
        }
        if (cat == null) {
            for (String[] row : CAT_KEYS) {
                String catName = row[0];
                boolean hit = false;
                for (int i = 1; i < row.length; i++) {
                    if (hay.contains(row[i])) { hit = true; break; }
                }
                if (hit) {
                    for (Models.Cat c : course.cats) {
                        if (c.name.equals(catName)) { cat = c; break; }
                    }
                    if (cat != null) break;
                }
            }
        }
        if (cat == null) cat = course.cats.get(0);
        return cat;
    }
}
