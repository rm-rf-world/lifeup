package com.hdh.lifeup.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hdh.lifeup.dao.LikeCountUserMapper;
import com.hdh.lifeup.dao.LikeMemberRecordMapper;
import com.hdh.lifeup.dao.LikeTeamTaskMapper;
import com.hdh.lifeup.model.constant.TaskConst.ActivityIcon;
import com.hdh.lifeup.model.domain.LikeCountUserDO;
import com.hdh.lifeup.model.domain.LikeMemberRecordDO;
import com.hdh.lifeup.model.domain.LikeTeamTaskDO;
import com.hdh.lifeup.model.dto.TeamMemberRecordDTO;
import com.hdh.lifeup.model.dto.TeamTaskDTO;
import com.hdh.lifeup.model.vo.TeamActivityRankVO;
import com.hdh.lifeup.redis.RedisOperator;
import com.hdh.lifeup.redis.UserKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * AsyncTaskService class<br/>
 * 异步任务类
 * @author hdonghong
 * @since 2019/06/05
 */
@Slf4j
@Component
public class AsyncTaskService {

    @Autowired
    private LikeMemberRecordMapper likeMemberRecordMapper;

    @Autowired
    private LikeCountUserMapper likeCountUserMapper;

    @Autowired
    private RedisOperator redisOperator;

    @Autowired
    private TeamTaskService teamTaskService;

    @Autowired
    @Lazy
    private TeamMemberService teamMemberService;

    @Autowired
    private LikeTeamTaskMapper likeTeamTaskMapper;

    /**
     * 点赞动态
     * @param userId
     * @param memberRecordDTO
     */
    @Async("taskExecutor")
    public void doLike(Long userId, TeamMemberRecordDTO memberRecordDTO){
        LikeMemberRecordDO likeMemberRecordDO = new LikeMemberRecordDO()
                .setMemberRecordId(memberRecordDTO.getMemberRecordId())
                .setUserId(userId);
        Integer result = likeMemberRecordMapper.insert(likeMemberRecordDO);
        if (!Objects.equals(result, 1)) {
            log.error("【doLike】likeMemberRecordMapper.insert failed. userId = [{}], memberRecordDTO = [{}]",
                    userId, memberRecordDTO);
            return;
        }
        result = likeCountUserMapper.incr(userId, 1);
        if (!Objects.equals(result, 1)) {
            LikeCountUserDO likeCountUserDO = new LikeCountUserDO()
                    .setUserId(memberRecordDTO.getUserId())
                    .setLikeCount(1);
            likeCountUserMapper.insert(likeCountUserDO);
        }
    }

    /**
     * 点赞团队
     * @param userId
     * @param teamTaskDTO
     */
    @Async("taskExecutor")
    public void doLike(Long userId, TeamTaskDTO teamTaskDTO){
        LikeTeamTaskDO likeTeamTaskDO = new LikeTeamTaskDO()
                .setTeamId(teamTaskDTO.getTeamId())
                .setUserId(userId);
        Integer result = likeTeamTaskMapper.insert(likeTeamTaskDO);
        if (!Objects.equals(result, 1)) {
            log.error("【doLike】likeTeamTaskMapper.insert failed. userId = [{}], teamTaskDTO = [{}]",
                    userId, teamTaskDTO);
            return;
        }
    }

    /**
     * 取消点赞动态
     * @param userId
     * @param memberRecordDTO
     */
    @Async("taskExecutor")
    public void undoLike(Long userId, TeamMemberRecordDTO memberRecordDTO) {
        QueryWrapper<LikeMemberRecordDO> queryWrapper = new QueryWrapper<LikeMemberRecordDO>()
                .eq("member_record_id", memberRecordDTO.getMemberRecordId())
                .eq("user_id", userId);
        Integer result = likeMemberRecordMapper.delete(queryWrapper);
        if (!Objects.equals(result, 1)) {
            log.error("【undoLike】likeMemberRecordMapper.delete failed. userId = [{}], memberRecordDTO = [{}]",
                    userId, memberRecordDTO);
            return;
        }
        likeCountUserMapper.incr(userId, -1);
    }

    /**
     * 取消点赞动态
     * @param userId
     * @param teamTaskDTO
     */
    @Async("taskExecutor")
    public void undoLike(Long userId, TeamTaskDTO teamTaskDTO) {
        QueryWrapper<LikeTeamTaskDO> queryWrapper = new QueryWrapper<LikeTeamTaskDO>()
                .eq("team_id", teamTaskDTO.getTeamId())
                .eq("user_id", userId);
        Integer result = likeTeamTaskMapper.delete(queryWrapper);
        if (!Objects.equals(result, 1)) {
            log.error("【undoLike】likeMemberRecordMapper.deleteById failed. userId = [{}], memberRecordDTO = [{}]",
                    userId, teamTaskDTO);
            return;
        }
    }

    /**
     * 点赞量兑换
     * @param userId
     * @param count
     */
    @Async("taskExecutor")
    public void exchangeLike(Long userId, int count) {
//        redisOperator.decrby(UserKey.LIKE_COUNT, userId, count);
        redisOperator.incrby(UserKey.LIKE_COUNT_EXCHANGED, userId, count);
        likeCountUserMapper.incr(userId, -count);
    }

    /**
     * 更新团队活跃度排序值
     * @param teamId
     * @param userId
     * @param activityIcon
     */
    @Async("taskExecutor")
    public void updateTeamRank(Long teamId, Long userId, Integer activityIcon) {
        TeamActivityRankVO teamActivityRankVO = new TeamActivityRankVO();
        if (ActivityIcon.IC_NEW.equals(activityIcon)) {
            // 查创建者过去30天参与团队数
            int ownerTeamCount = teamMemberService.countUserLast30DaysTeams(userId);

            // 查创建者过去30天发表总动态数
            int ownerActivityCount = teamMemberService.countUserLast30DaysRecords(userId);

            teamActivityRankVO.setActivityCount(1)
                    .setTeamMemberCount(1)
                    .setOwnerTeamCount(ownerTeamCount)
                    .setOwnerActivityCount(ownerActivityCount);
        } else if (ActivityIcon.IC_JOIN.equals(activityIcon)) {
            teamActivityRankVO.setActivityCount(1)
                    .setTeamMemberCount(1);

        } else if (ActivityIcon.IC_SIGN.equals(activityIcon)) {
            teamActivityRankVO.setActivityCount(1);

        } else {
            return;
        }
        teamTaskService.incrTeamRank(teamId, teamActivityRankVO.getTeamRank());
    }

}
