package searchengine.dto.statistics;

import lombok.Data;

@Data
public class TotalStatistics {
    private long sites;
    private long pages;
    private long lemmas;
    private boolean indexing;
}
