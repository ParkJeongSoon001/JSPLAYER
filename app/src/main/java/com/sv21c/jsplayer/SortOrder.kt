package com.sv21c.jsplayer

enum class SortOrder(val displayName: String) {
    NAME_ASC("이름순 (오름차순)"),
    NAME_DESC("이름순 (내림차순)"),
    DATE_DESC("최신순 (최신)"),
    DATE_ASC("최신순 (덜최신)"),
    PLAYED_DESC("최근 재생순")
}
