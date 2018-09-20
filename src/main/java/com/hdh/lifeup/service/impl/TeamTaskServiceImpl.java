package com.hdh.lifeup.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.base.Preconditions;
import com.hdh.lifeup.auth.UserContext;
import com.hdh.lifeup.base.BaseDTO;
import com.hdh.lifeup.domain.TeamRecordDO;
import com.hdh.lifeup.domain.TeamTaskDO;
import com.hdh.lifeup.dto.*;
import com.hdh.lifeup.enums.CodeMsgEnum;
import com.hdh.lifeup.exception.GlobalException;
import com.hdh.lifeup.mapper.TeamRecordMapper;
import com.hdh.lifeup.mapper.TeamTaskMapper;
import com.hdh.lifeup.service.TeamMemberService;
import com.hdh.lifeup.service.TeamTaskService;
import com.hdh.lifeup.service.UserInfoService;
import com.hdh.lifeup.vo.ActivityVO;
import com.hdh.lifeup.vo.NextSignVO;
import com.hdh.lifeup.vo.TeamDetailVO;
import com.hdh.lifeup.vo.TeamTaskVO;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static com.hdh.lifeup.constant.TaskConst.*;

/**
 * TeamTaskServiceImpl class<br/>
 *
 * @author hdonghong
 * @since 2018/09/03
 */
@Slf4j
@Service
public class TeamTaskServiceImpl implements TeamTaskService {

    private TeamTaskMapper teamTaskMapper;

    private TeamRecordMapper teamRecordMapper;

    private TeamMemberService memberService;

    private UserInfoService userInfoService;

    @Autowired
    public TeamTaskServiceImpl(TeamTaskMapper teamTaskMapper,
                               TeamRecordMapper teamRecordMapper,
                               TeamMemberService teamMemberService,
                               UserInfoService userInfoService) {
        this.teamTaskMapper = teamTaskMapper;
        this.teamRecordMapper = teamRecordMapper;
        this.memberService = teamMemberService;
        this.userInfoService = userInfoService;
    }

    @Override
    public TeamTaskDTO getOne(Long teamId) {
        Preconditions.checkNotNull(teamId, "[TeamTaskServiceImpl.getOne] teamId is null");
        TeamTaskDO teamTaskDO = teamTaskMapper.selectById(teamId);
        if (teamTaskDO == null) {
            log.error("【TeamTaskServiceImpl.getOne】不存在的团队任务，teamId = [{}]", teamId);
            throw new GlobalException(CodeMsgEnum.TEAM_NOT_EXIST);
        }
        return BaseDTO.from(teamTaskDO, TeamTaskDTO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TeamTaskDTO insert(@NonNull TeamTaskDTO teamTaskDTO) {
        TeamTaskDO teamTaskDO = teamTaskDTO.toDO(TeamTaskDO.class);
        Integer result = teamTaskMapper.insert(teamTaskDO);
        if (!Objects.equals(1, result)) {
            log.error("【TeamTaskServiceImpl.insert】插入新teamTask记录失败，teamTaskDTO = [{}]", teamTaskDTO);
            throw new GlobalException(CodeMsgEnum.DATABASE_EXCEPTION);
        }
        teamTaskDTO.setTeamId(teamTaskDO.getTeamId());
        return teamTaskDTO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NextSignVO addTeam(@NonNull TeamTaskVO teamTaskVO) {
        TeamTaskDTO teamTaskDTO = new TeamTaskDTO();
        BeanUtils.copyProperties(teamTaskVO, teamTaskDTO, "firstStartTime", "firstEndTime");

        // 存主团队表
        LocalDateTime firstStartTime = teamTaskVO.getFirstStartTime();
        LocalDateTime firstEndTime = teamTaskVO.getFirstEndTime();
        teamTaskDTO.setFirstStartTime(firstStartTime)
                   .setFirstEndTime(firstEndTime)
                   .setUserId(UserContext.get().getUserId())
                   .setTeamHead(UserContext.get().getUserHead());
        this.insert(teamTaskDTO);
        Long teamId = teamTaskDTO.getTeamId();

        // 先获取下一次签到的VO
        NextSignVO nextSign = this.getNextSign(teamTaskDTO);

        // 创建者作为新成员写入成员表
        TeamMemberDTO memberDTO = new TeamMemberDTO()
                                            .setTeamId(teamId)
                                            .setTeamRole(TeamRole.MEMBER);
        // 创建者默认发布第一条团队成员动态
        TeamMemberRecordDTO memberRecordDTO = new TeamMemberRecordDTO()
                                            .setTeamId(teamId)
                                            .setTeamTitle(teamTaskDTO.getTeamTitle())
                                            .setTeamRecordId(nextSign.getTeamRecordId())
                                            .setUserActivity("创建了团队「" +  teamTaskVO.getTeamTitle() +"」")
                                            .setActivityIcon(ActivityIcon.IC_NEW);
        memberService.addMember(memberDTO, memberRecordDTO);

        return nextSign;

    }

    @Override
    public PageDTO<TeamTaskDTO> page(PageDTO pageDTO) {
        log.info("pageNo = " + pageDTO.getCurrentPage());
        IPage<TeamTaskDO> taskDOPage = teamTaskMapper.selectPage(
                new Page<>(pageDTO.getCurrentPage(), pageDTO.getSize()),
                new QueryWrapper<TeamTaskDO>().orderByDesc("create_time")
        );
        return PageDTO.create(taskDOPage, TeamTaskDTO.class);
    }

    @Override
    public TeamDetailVO getDetail(@NonNull Long teamId) {
        TeamTaskDTO teamTaskDTO = this.getOne(teamId);
        int memberAmount = memberService.countMembersByTeamId(teamId);
        UserInfoDTO owner = userInfoService.getOne(teamTaskDTO.getUserId());
        NextSignVO nextSign;
        try {
            nextSign = this.getNextSign(teamTaskDTO);
        } catch (GlobalException e) {
            nextSign = new NextSignVO();
        }

        TeamDetailVO teamDetailVO = new TeamDetailVO();
        BeanUtils.copyProperties(teamTaskDTO, teamDetailVO);
        teamDetailVO.setMemberAmount(memberAmount)
                    .setOwner(owner)
                    .setNextStartTime(nextSign.getNextStartTime())
                    .setNextEndTime(nextSign.getNextEndTime())
                    .setIsMember(memberService.isMember(teamId, UserContext.get().getUserId()));
        return teamDetailVO;
    }

    @Override
    public NextSignVO getNextSign(Long teamId) {
        // 验证团队是否存在
        TeamTaskDTO teamTaskDTO = this.getOne(teamId);
        return this.getNextSign(teamTaskDTO);
    }

    private NextSignVO getNextSign(TeamTaskDTO teamTaskDTO) {
        List<TeamRecordDO> teamRecordDOList = teamRecordMapper.selectList(
                new QueryWrapper<TeamRecordDO>().eq("team_id", teamTaskDTO.getTeamId())
                                                .gt("next_end_time", LocalDateTime.now())
                                                .orderByAsc("create_time")
        );

        // 如果没有下一次的签到信息，就直接生成后返回
        NextSignVO nextSignVO = new NextSignVO()
                .setTeamTitle(teamTaskDTO.getTeamTitle())
                .setRewardAttrs(teamTaskDTO.getRewardAttrs())
                .setRewardExp(teamTaskDTO.getRewardExp())
                .setTeamFreq(teamTaskDTO.getTeamFreq());

        if (CollectionUtils.isEmpty(teamRecordDOList)) {
            BeanUtils.copyProperties(this.addTeamRecord(teamTaskDTO, 1), nextSignVO);
            return nextSignVO;
        }

        // 假设还没有签到最近一次的
        TeamRecordDO teamRecordSrc = teamRecordDOList.get(0);
        int result = memberService.hasSignedIn(teamRecordSrc.getTeamRecordId(), UserContext.get().getUserId());
        // 判断是否已经签到了，result == 1说明学员已经签到了最近一次
        if (result == 1) {
            // 如果当前已经生成了下下次签到的记录就直接返回，否则生成下下次签到记录
            teamRecordSrc = (teamRecordDOList.size() == 2) ?
                    teamRecordDOList.get(1) : this.addTeamRecord(teamTaskDTO, 2);
        }
        BeanUtils.copyProperties(teamRecordSrc, nextSignVO);
        return nextSignVO;
    }

    @Override
    public NextSignVO joinTeam(Long teamId) {
        TeamTaskDTO teamTaskDTO = this.getOne(teamId);
        if (LocalDate.now().isAfter(teamTaskDTO.getStartDate())) {
            log.error("【加入团队】已超过加入团队的截止日期，startDate() = [{}]", teamTaskDTO.getStartDate());
            throw new GlobalException(CodeMsgEnum.DATABASE_EXCEPTION);
        }

        NextSignVO nextSign = this.getNextSign(teamTaskDTO);
        // 创建者作为新成员写入成员表
        TeamMemberDTO memberDTO = new TeamMemberDTO()
                .setTeamId(teamId)
                .setTeamRole(TeamRole.MEMBER);
        // 创建者默认发布第一条团队成员动态
        TeamMemberRecordDTO memberRecordDTO = new TeamMemberRecordDTO()
                .setTeamId(teamId)
                .setTeamTitle(teamTaskDTO.getTeamTitle())
                .setTeamRecordId(nextSign.getTeamRecordId())
                .setUserActivity("欢迎"+ UserContext.get().getNickname() +"加入团队[" +  nextSign.getTeamTitle() +"]")
                .setActivityIcon(ActivityIcon.IC_JOIN);
        memberService.addMember(memberDTO, memberRecordDTO);
        return nextSign;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NextSignVO signIn(Long teamId, ActivityVO activityVO) {
        TeamTaskDTO teamTaskDTO = this.getOne(teamId);
        NextSignVO nextSign = this.getNextSign(teamTaskDTO);
        LocalDateTime nowTime = LocalDateTime.now();

        if (nowTime.isBefore(nextSign.getNextStartTime())) {
            // 未到签到时间
            throw new GlobalException(CodeMsgEnum.NOT_SIGN_TIME);

        } else if (nowTime.isAfter(nextSign.getNextEndTime())) {
            // 超过签到时间
            log.error("【团队签到】学员逾期签到，nowTime = [{}], nextSign = [{}]", nowTime, nextSign);
            throw new GlobalException(CodeMsgEnum.NOT_SIGN_TIME);

        } else {
            // 可以签到
            TeamMemberRecordDTO memberRecordDTO = new TeamMemberRecordDTO()
                    .setTeamId(teamId)
                    .setTeamTitle(teamTaskDTO.getTeamTitle())
                    .setTeamRecordId(nextSign.getTeamRecordId())
                    .setUserActivity(activityVO.getActivity())
                    .setActivityIcon(ActivityIcon.IC_SIGN);
            memberService.addMemberRecord(memberRecordDTO);
        }

        return this.getNextSign(teamTaskDTO);
    }

    @Override
    public List<NextSignVO> getAllNextSigns(Long memberId) {
        return null;
    }

    @Override
    public TeamTaskDTO update(TeamTaskDTO dto) {
        return null;
    }

    @Override
    public TeamTaskDTO deleteLogically(Long aLong) {
        return null;
    }

    @Override
    public TeamTaskDTO delete(Long aLong) {
        return null;
    }

    /**
     * 由团队添加下n次团队签到记录
     * n可能为1，即下一次的团队签到记录
     * n可能为2，即下下一次的团队签到记录
     * @param teamTaskDTO 团队信息
     */
    private TeamRecordDO addTeamRecord(TeamTaskDTO teamTaskDTO, int n) {
        LocalDateTime firstStartTime = teamTaskDTO.getFirstStartTime();
        LocalDateTime nowTime = LocalDateTime.now();

        // 计算下n次签到的日期时间
        LocalDate firstStartDate = firstStartTime.toLocalDate();
        LocalDate nowDate = nowTime.toLocalDate();
        long period = nowDate.toEpochDay() - firstStartDate.toEpochDay();
        int teamFreq = teamTaskDTO.getTeamFreq();

        boolean isEnd = (teamFreq == 0 && period > 0) || teamTaskDTO.getCompleteTime() != null;
        if (isEnd) {
            // 如果是不重复的团队任务，并且已经过了签到日期或者有了完成时间，则提示已经结束
            throw new GlobalException(CodeMsgEnum.TEAM_IS_END);
        }
        long nextSignPeriod = (teamFreq == 0) ?
                0 : (long) Math.ceil((period * 1.0) / teamFreq) * teamFreq + teamFreq * n;
        LocalDateTime nextStartTime = firstStartTime.plusDays(nextSignPeriod);
        LocalDateTime nextEndTime = teamTaskDTO.getFirstEndTime().plusDays(nextSignPeriod);

        // 插入下n次的团队签到记录
        TeamRecordDO nextTeamRecordDO = new TeamRecordDO().setNextStartTime(nextStartTime)
                                                      .setNextEndTime(nextEndTime)
                                                      .setTeamId(teamTaskDTO.getTeamId());
        teamRecordMapper.insert(nextTeamRecordDO);

        return nextTeamRecordDO;
    }
}