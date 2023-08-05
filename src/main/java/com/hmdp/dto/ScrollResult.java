package com.hmdp.dto;

import lombok.Data;

import java.util.List;


/*
返回给用户的feed列表结构
 */
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
