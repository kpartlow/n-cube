package com.cedarsoftware.ncube

import groovy.transform.CompileStatic

/**
 * Class used to hold column difference (delta) information.
 *
 * @author John DeRegnaucourt (jdereg@gmail.com)
 *         <br/>
 *         Copyright (c) Cedar Software LLC
 *         <br/><br/>
 *         Licensed under the Apache License, Version 2.0 (the "License")
 *         you may not use this file except in compliance with the License.
 *         You may obtain a copy of the License at
 *         <br/><br/>
 *         http://www.apache.org/licenses/LICENSE-2.0
 *         <br/><br/>
 *         Unless required by applicable law or agreed to in writing, software
 *         distributed under the License is distributed on an "AS IS" BASIS,
 *         WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *         See the License for the specific language governing permissions and
 *         limitations under the License.
 */
@CompileStatic
class ColumnDelta
{
    AxisType axisType
    Column column
    String changeType
    Comparable locatorKey

    ColumnDelta(AxisType axisType, Column column, Comparable locatorKey, String changeType)
    {
        this.axisType = axisType
        this.column = column
        this.locatorKey = locatorKey
        this.changeType = changeType
    }

    String toString()
    {
        return "${axisType}/${changeType}/${column.toString()}/${locatorKey}"
    }
}
