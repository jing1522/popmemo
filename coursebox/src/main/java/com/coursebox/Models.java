package com.coursebox;

import java.util.ArrayList;
import java.util.List;

/** 课程 / 格子 / 材料条目 */
public class Models {

    public static class Item {
        public String name;   // 文件名
        public String rel;    // 匣子内相对路径
        public long size;
        public long at;       // 收进时间
        public String sha;    // 内容哈希，用于去重
    }

    public static class Cat {
        public String id;
        public String name;
        public String icon;   // emoji
        public List<Item> items = new ArrayList<>();
    }

    public static class Course {
        public String id;
        public String name;
        public int color;     // PALETTES 下标
        public List<Cat> cats = new ArrayList<>();

        public Cat cat(String cid) {
            for (Cat c : cats) if (c.id.equals(cid)) return c;
            return null;
        }

        public int count() {
            int n = 0;
            for (Cat c : cats) n += c.items.size();
            return n;
        }
    }
}
