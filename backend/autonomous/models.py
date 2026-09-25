from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
from uuid import UUID
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

class ScheduleInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    schedule_id: UUID
    profile_id: UUID
    number: str = Field(pattern=r'^\+[1-9][0-9]{7,14}$')
    timezone: str
    first_call_at: datetime
    recurrence: str = Field(pattern='^(once|daily)$')
    enabled: bool = False
    permission_to_call: bool
    cloud_processing_approved: bool
    rawtree_storage_approved: bool
    resource_search_approved: bool
    participant_preconsented_demo: bool = False
    data_origin: str = Field(default='consented_demo', pattern='^(consented_demo|real)$')
    city: str = Field(default='',max_length=100,pattern=r"^[\w .,()'’-]*$")

    @field_validator('timezone')
    @classmethod
    def zone(cls,value):
        try: ZoneInfo(value)
        except ZoneInfoNotFoundError: raise ValueError('Use an IANA timezone')
        return value

    @model_validator(mode='after')
    def permissions(self):
        if self.first_call_at.tzinfo is None: raise ValueError('Call time requires an explicit UTC offset')
        if self.enabled and not all((self.permission_to_call,self.cloud_processing_approved,self.rawtree_storage_approved)):
            raise ValueError('Calling, cloud processing and RawTree storage permissions are required')
        if self.participant_preconsented_demo and self.data_origin!='consented_demo':
            raise ValueError('Prior demo consent cannot authorize real parent calls')
        return self

def next_daily(previous: datetime, zone: str, after: datetime):
    """Same local wall time; skip nonexistent DST times, use first repeated time."""
    tz=ZoneInfo(zone); local=previous.astimezone(tz); day=local.date()
    for _ in range(370):
        day += timedelta(days=1)
        candidate=datetime.combine(day,local.time().replace(tzinfo=None),tzinfo=tz).replace(fold=0)
        utc=candidate.astimezone(timezone.utc)
        if utc.astimezone(tz).replace(tzinfo=None)!=candidate.replace(tzinfo=None): continue
        if utc>after: return utc
    raise ValueError('Unable to determine next call')

class Evidence(BaseModel):
    model_config=ConfigDict(extra='forbid')
    session_id: str
    quote: str=Field(min_length=1,max_length=300)

class Change(BaseModel):
    model_config=ConfigDict(extra='forbid')
    description: str=Field(min_length=1,max_length=600)
    evidence: list[Evidence]=Field(min_length=1,max_length=4)

class LiquidReport(BaseModel):
    model_config=ConfigDict(extra='forbid')
    summary: str=Field(min_length=1,max_length=1500)
    changes: list[Change]=Field(max_length=5)
    limitations: str=Field(min_length=1,max_length=600)
    resources_helpful: bool
    resource_reason: str=Field(max_length=600)
    concern_quote: str=Field(max_length=300)
    search_terms: str=Field(max_length=180,pattern=r'^[A-Za-z0-9 ,\-]*$')
