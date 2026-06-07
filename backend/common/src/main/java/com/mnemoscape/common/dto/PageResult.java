package com.mnemoscape.common.dto;

import java.util.List;

public class PageResult<T> {
    private List<T> items;
    private long total;
    private int page;
    private int size;
    private int totalPages;

    public PageResult() {
    }

    public PageResult(List<T> items, long total, int page, int size, int totalPages) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
        this.totalPages = totalPages;
    }

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> items) {
        this.items = items;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static class Builder<T> {
        private List<T> items;
        private long total;
        private int page;
        private int size;
        private int totalPages;

        public Builder<T> items(List<T> items) {
            this.items = items;
            return this;
        }

        public Builder<T> total(long total) {
            this.total = total;
            return this;
        }

        public Builder<T> page(int page) {
            this.page = page;
            return this;
        }

        public Builder<T> size(int size) {
            this.size = size;
            return this;
        }

        public Builder<T> totalPages(int totalPages) {
            this.totalPages = totalPages;
            return this;
        }

        public PageResult<T> build() {
            return new PageResult<>(items, total, page, size, totalPages);
        }
    }

    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        return PageResult.<T>builder()
                .items(items)
                .total(total)
                .page(page)
                .size(size)
                .totalPages((int) Math.ceil((double) total / size))
                .build();
    }
}
