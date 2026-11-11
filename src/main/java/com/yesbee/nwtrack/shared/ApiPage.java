package com.yesbee.nwtrack.shared;

import java.util.List;
import java.util.function.Function;

public record ApiPage<T>(List<T> items,int page,int size,long totalElements){
    public <R> ApiPage<R> map(Function<T,R> mapper){return new ApiPage<>(items.stream().map(mapper).toList(),page,size,totalElements);}
}
