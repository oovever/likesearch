package org.likeserch;


import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * 模糊查询工具类
 *
 * @author rocmu
 * @date 2023/04/24
 */
public class LikeSearch<T> {

    private final ReentrantReadWriteLock reentrantReadWriteLock = new ReentrantReadWriteLock();
    private final Lock rLock = reentrantReadWriteLock.readLock();
    private final Lock wLock = reentrantReadWriteLock.writeLock();

    private final Node<T>[] nodes = new Node[Character.MAX_VALUE];
    private final Map<T, Set<String>> targetWordMap = new HashMap<>();

    public void put(T targetWord, String searchWord) {
        wLock.lock();
        try {
            if (targetWordMap.containsKey(targetWord) && targetWordMap.get(targetWord).contains(searchWord)) {
                return;
            }
            char[] charArray = searchWord.toCharArray();
            for (int i = 0; i < charArray.length; i++) {
                char c = charArray[i];
                Node<T> node = nodes[c];
                if (node == null) {
                    node = new Node<T>();
                    nodes[c] = node;
                }
                node.add(targetWord, (short) i);
            }
            if (!targetWordMap.containsKey(targetWord)) {
                targetWordMap.put(targetWord, new HashSet<>());
            }
            targetWordMap.get(targetWord).add(searchWord);
        } finally {
            wLock.unlock();
        }

    }

    public boolean remove(T targetWord) {
        wLock.lock();
        try {
            if (!targetWordMap.containsKey(targetWord)) {
                return false;
            }
            boolean success = false;
            for (String searchWord : targetWordMap.get(targetWord)) {
                char[] searchWordArr = searchWord.toCharArray();
                for (char wordChar : searchWordArr) {
                    if (nodes[wordChar] != null) {
                        success = nodes[wordChar].remove(targetWord);
                    }
                }
            }
            targetWordMap.remove(targetWord);
            return success;
        } finally {
            wLock.unlock();
        }

    }

    public void update(T removeWord, T newTargetWord, String newSearchWord) {
        wLock.lock();
        try {
            boolean removeResult = remove(removeWord);
            targetWordMap.remove(removeWord);
            if (removeResult) {
                put(newTargetWord, newSearchWord);
            }
        } finally {
            wLock.unlock();
        }
    }

    public Collection<T> search(String searchWord, int limit, LikeSearchType searchType) {
        rLock.lock();
        try {
            char[] charArray = searchWord.toCharArray();
            int matchSize = 0;
            ResultContext context = new ResultContext();
            for (char c : charArray) {
                Node<T> node = nodes[c];
                if (node == null) {
                    break;
                }
                if (!context.match(node, searchType, searchWord)) {
                    break;
                }
                matchSize++;
            }
            if (matchSize == searchWord.length()) {
                return context.limit(limit);
            }
            return Collections.emptyList();
        } finally {
            rLock.unlock();
        }

    }

    public enum LikeSearchType {

        EXACT_MATCH(0, "精确匹配"),
        LIKE_MATCH(1, "模糊匹配"), // 匹配字符必须连续，比如 "天气好"与"天气"、"气好"匹配，但是与"天好不配"
        SUPER_LIKE_MATCH(2, "模糊匹配"); // "匹配字符可以不连续,比如 "天气好"可以和"天好匹配""


        private final int value;
        private final String desc;

        LikeSearchType(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }
    }

    private static class Node<T> {

        Map<T, CachedHashSet<Short>> indexMap = new HashMap<>();

        private void add(T targetWord, Short index) {
            Set<Short> indexSet = indexMap.computeIfAbsent(targetWord, k -> new CachedHashSet<>());
            indexSet.add(index);
        }

        private boolean remove(T targetWord) {
            if (indexMap.containsKey(targetWord)) {
                indexMap.remove(targetWord);
                return true;
            }
            return false;
        }

    }

    private static class CachedHashSet<E> extends LinkedHashSet<E> {

        private E last = null;

        @Override
        public boolean add(E e) {
            last = e;
            return super.add(e);
        }

        public E getLast() {
            return last;
        }

    }

    private class ResultContext {

        Map<T, Set<Short>> result;
        boolean isFirstIndex = false;

        private boolean match(Node<T> node, LikeSearchType searchType, String searchWord) {
            if (!isFirstIndex) {
                result = new HashMap<>(node.indexMap);
                isFirstIndex = true;
                return true;
            }
            boolean isMatch = false;
            Map<T, Set<Short>> newResult = new HashMap<>();
            for (Entry<T, CachedHashSet<Short>> entry : node.indexMap.entrySet()) {
                if (!result.containsKey(entry.getKey())) { // 之前的targetValue不包含当前的
                    continue;
                }
                Set<Short> beforeIndexSet = result.get(entry.getKey());
                boolean currentMatch = false;
                for (Short beforeIndex : beforeIndexSet) {
                    if (containIndex(entry, ++beforeIndex, searchType, searchWord)) {
                        currentMatch = true;
                        break;
                    }
                }
                if (currentMatch) {
                    newResult.put(entry.getKey(), entry.getValue());
                    isMatch = true;
                }
            }
            result = newResult;
            return isMatch;
        }

        private Collection<T> limit(int limit) {
            if (limit <= 0) {
                return Collections.emptyList();
            }
            if (result.size() <= limit) {
                return result.keySet().stream().sorted().collect(Collectors.toList());
            }
            return result.keySet().stream().sorted().collect(Collectors.toList()).subList(0, limit);
        }

        public boolean containIndex(Entry<T, CachedHashSet<Short>> currentIndexEntry, Short expectIndex,
                LikeSearchType searchType, String searchWord) {
            switch (searchType) {
                case EXACT_MATCH:
                    return currentIndexEntry.getValue().contains(expectIndex) && currentIndexEntry.getKey()
                            .equals(searchWord);
                case LIKE_MATCH:
                    return currentIndexEntry.getValue().contains(expectIndex);
                default:
                    return currentIndexEntry.getValue().getLast() >= expectIndex;
            }
        }
    }
}
